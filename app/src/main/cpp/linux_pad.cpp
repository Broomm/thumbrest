#include "linux_pad.h"
#include "source_visibility.h"

#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <charconv>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <stdexcept>
#include <system_error>
#include <thread>
#include <utility>

namespace thumbrest {
namespace {

constexpr char kVirtualName[] = "Thumbrest Virtual Gamepad";
constexpr char kVirtualPort[] = "app.thumbrest/gamepad";
constexpr std::array<unsigned, ButtonCount> kKeyCodes{
    BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST, BTN_TL, BTN_TR, BTN_TL2, BTN_TR2,
    BTN_THUMBL, BTN_THUMBR, BTN_SELECT, BTN_START, BTN_DPAD_UP, BTN_DPAD_DOWN,
    BTN_DPAD_LEFT, BTN_DPAD_RIGHT, BTN_MODE,
};
constexpr std::array<unsigned, 16> kAxes{
    ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_Z, ABS_RZ, ABS_GAS, ABS_BRAKE,
    ABS_THROTTLE, ABS_RUDDER, ABS_WHEEL, ABS_HAT0X, ABS_HAT0Y,
    ABS_HAT1X, ABS_HAT1Y, ABS_PRESSURE,
};

class Fd {
public:
    explicit Fd(int value = -1) : value_(value) {}
    ~Fd() { reset(); }
    Fd(Fd&& other) noexcept : value_(std::exchange(other.value_, -1)) {}
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
    Fd& operator=(Fd&& other) noexcept {
        if (this != &other) { reset(); value_ = std::exchange(other.value_, -1); }
        return *this;
    }

    int get() const { return value_; }
    explicit operator bool() const { return value_ >= 0; }
    void reset() {
        if (value_ >= 0) close(std::exchange(value_, -1));
    }

private:
    int value_;
};

[[noreturn]] void kernelError(const char* message, int error = errno) {
    throw std::system_error(error, std::generic_category(), message);
}

template <typename T>
void requireIoctl(int fd, unsigned long request, T arg, const char* message) {
    if (ioctl(fd, request, arg) < 0) kernelError(message);
}

template <unsigned Maximum>
struct KernelBits {
    std::array<unsigned long, Maximum / (sizeof(unsigned long) * 8) + 1> words{};
    bool has(unsigned code) const {
        constexpr auto width = sizeof(unsigned long) * 8;
        return code <= Maximum && ((words[code / width] >> (code % width)) & 1UL);
    }
};

Fd openSource(int node) {
    const auto path = "/dev/input/event" + std::to_string(node);
    return Fd(open(path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW));
}

std::string identity(int fd) {
    struct stat info{};
    if (fstat(fd, &info) < 0) kernelError("Read controller identity");
    if (!S_ISCHR(info.st_mode)) throw std::runtime_error("Controller is not an input device");
    return std::to_string(info.st_dev) + ":" + std::to_string(info.st_ino) + ":" +
        std::to_string(info.st_rdev);
}

std::string hardwareIdentity(int fd) {
    input_id id{};
    requireIoctl(fd, EVIOCGID, &id, "Read controller hardware identity");
    const auto encode = [](const std::array<char, 256>& value) {
        constexpr char hex[] = "0123456789abcdef";
        std::string result;
        for (const auto ch : value) {
            if (!ch) break;
            const auto byte = static_cast<unsigned char>(ch);
            result += hex[byte >> 4];
            result += hex[byte & 15];
        }
        return result;
    };
    std::array<char, 256> unique{}, physical{};
    // Some USB and built-in pads do not expose either optional string. The
    // caller must refuse automatic selection when the resulting ID is ambiguous.
    ioctl(fd, EVIOCGUNIQ(unique.size() - 1), unique.data());
    ioctl(fd, EVIOCGPHYS(physical.size() - 1), physical.data());
    return std::to_string(id.bustype) + ":" + std::to_string(id.vendor) + ":" +
        std::to_string(id.product) + ":" + std::to_string(id.version) + ":" +
        encode(unique) + ":" + encode(physical);
}

std::string controllerName(int fd) {
    std::array<char, 128> name{};
    requireIoctl(fd, EVIOCGNAME(name.size() - 1), name.data(), "Read controller name");
    std::string result(name.data());
    for (auto& ch : result) {
        if (static_cast<unsigned char>(ch) < 32 || static_cast<unsigned char>(ch) >= 127) {
            ch = '?';
        }
    }
    return result.empty() ? "Gamepad" : result;
}

struct Capabilities {
    Buttons digital = 0;
    Buttons supported = 0;
    input_id id{};
    std::vector<unsigned> extraKeys;
    std::array<bool, ABS_CNT> axis{};
    std::array<input_absinfo, ABS_CNT> info{};
};

Capabilities describe(int fd) {
    KernelBits<KEY_MAX> keys;
    requireIoctl(fd, EVIOCGBIT(EV_KEY, sizeof(keys.words)), keys.words.data(),
                 "Read controller buttons");
    Capabilities cap;
    for (unsigned index = 0; index < kKeyCodes.size(); ++index) {
        if (keys.has(kKeyCodes[index])) cap.digital |= Buttons{1} << index;
    }

    constexpr auto faces = bit(A) | bit(B) | bit(X) | bit(Y);
    // Gamepads may share a name with keys, motion sensors, and touchpads.
    if (!(cap.digital & faces)) return {};

    cap.supported = cap.digital;
    requireIoctl(fd, EVIOCGID, &cap.id, "Read controller input mapping identity");
    for (unsigned code = 0; code <= KEY_MAX; ++code) {
        if (keys.has(code) && std::find(kKeyCodes.begin(), kKeyCodes.end(), code) == kKeyCodes.end()) {
            cap.extraKeys.push_back(code);
        }
    }
    KernelBits<ABS_MAX> axes;
    requireIoctl(fd, EVIOCGBIT(EV_ABS, sizeof(axes.words)), axes.words.data(),
                 "Read controller axes");
    for (const auto code : kAxes) {
        if (!axes.has(code)) continue;
        auto& info = cap.info[code];
        requireIoctl(fd, EVIOCGABS(code), &info, "Read controller axis range");
        cap.axis[code] = info.minimum < info.maximum;
    }
    if (cap.axis[ABS_HAT0X]) cap.supported |= bit(Left) | bit(Right);
    if (cap.axis[ABS_HAT0Y]) cap.supported |= bit(Up) | bit(Down);
    return cap;
}

Fd openVirtual() {
    constexpr int flags = O_WRONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW;
    Fd first(open("/dev/uinput", flags));
    if (first) return first;
    const int firstError = errno;
    Fd alternate(open("/dev/input/uinput", flags));
    if (alternate) return alternate;
    kernelError("Open uinput (verify Magisk access and device kernel support)",
                errno == ENOENT ? firstError : errno);
}

bool isHat(unsigned axis) { return axis == ABS_HAT0X || axis == ABS_HAT0Y; }

int neutralAxis(unsigned code, const input_absinfo& info) {
    switch (code) {
        case ABS_GAS:
        case ABS_BRAKE:
        case ABS_THROTTLE:
        case ABS_PRESSURE:
            return info.minimum;
        case ABS_Z:
        case ABS_RZ:
            if (info.minimum >= 0) return info.minimum;
            break;
        default:
            break;
    }
    if (info.minimum < 0 && info.maximum > 0) return 0;
    return static_cast<int>((static_cast<long long>(info.minimum) + info.maximum) / 2);
}

bool virtualAxis(const Capabilities& cap, unsigned axis) {
    if (axis == ABS_HAT0X) return cap.supported & (bit(Left) | bit(Right));
    if (axis == ABS_HAT0Y) return cap.supported & (bit(Up) | bit(Down));
    return cap.axis[axis];
}

Fd createVirtual(const Capabilities& cap) {
    auto fd = openVirtual();
    requireIoctl(fd.get(), UI_SET_PHYS, kVirtualPort, "Set the virtual controller input port");
    requireIoctl(fd.get(), UI_SET_EVBIT, EV_KEY, "Configure virtual gamepad buttons");
    for (unsigned index = 0; index < kKeyCodes.size(); ++index) {
        if (index >= Up && index <= Right) continue; // D-pad is exposed as a hat.
        if (cap.digital & (Buttons{1} << index)) {
            requireIoctl(fd.get(), UI_SET_KEYBIT, kKeyCodes[index], "Configure virtual button");
        }
    }
    for (const auto code : cap.extraKeys) {
        requireIoctl(fd.get(), UI_SET_KEYBIT, code, "Configure other physical controller keys");
    }
    for (const auto code : kAxes) {
        if (!virtualAxis(cap, code)) continue;
        requireIoctl(fd.get(), UI_SET_EVBIT, EV_ABS, "Configure virtual gamepad axes");
        requireIoctl(fd.get(), UI_SET_ABSBIT, code, "Configure virtual axis");
        uinput_abs_setup axis{};
        axis.code = static_cast<__u16>(code);
        axis.absinfo = cap.info[code];
        if (isHat(code)) {
            axis.absinfo = {};
            axis.absinfo.minimum = -1;
            axis.absinfo.maximum = 1;
        } else {
            axis.absinfo.value = neutralAxis(code, axis.absinfo);
        }
        requireIoctl(fd.get(), UI_ABS_SETUP, &axis, "Set virtual axis limits");
    }

    uinput_setup setup{};
    std::strncpy(setup.name, kVirtualName, sizeof(setup.name) - 1);
    // Preserve Android's vendor/product key-layout choice, including handheld
    // system buttons and its interpretation of centered stick/trigger axes.
    setup.id = cap.id;
    requireIoctl(fd.get(), UI_DEV_SETUP, &setup, "Set up virtual gamepad");
    requireIoctl(fd.get(), UI_DEV_CREATE, 0, "Create virtual gamepad");
    return fd;
}

struct SourceFrame {
    Buttons digital = 0;
    std::array<int, ABS_CNT> axes{};
    std::array<bool, KEY_CNT> extraKeys{};

    Buttons buttons() const {
        Buttons result = digital;
        if (axes[ABS_HAT0X] < 0) result |= bit(Left);
        if (axes[ABS_HAT0X] > 0) result |= bit(Right);
        if (axes[ABS_HAT0Y] < 0) result |= bit(Up);
        if (axes[ABS_HAT0Y] > 0) result |= bit(Down);
        return result;
    }
};

bool sample(int fd, const Capabilities& cap, SourceFrame& frame) {
    KernelBits<KEY_MAX> keys;
    if (ioctl(fd, EVIOCGKEY(sizeof(keys.words)), keys.words.data()) < 0) return false;

    frame = {};
    for (unsigned index = 0; index < kKeyCodes.size(); ++index) {
        if (keys.has(kKeyCodes[index])) frame.digital |= Buttons{1} << index;
    }
    for (const auto code : cap.extraKeys) frame.extraKeys[code] = keys.has(code);
    for (const auto code : kAxes) {
        if (!cap.axis[code]) continue;
        input_absinfo info{};
        if (ioctl(fd, EVIOCGABS(code), &info) < 0) return false;
        frame.axes[code] = info.value;
    }
    return true;
}

std::array<unsigned, 2> rightStickAxes(const Capabilities& cap) {
    if (cap.axis[ABS_RX] && cap.axis[ABS_RY]) return {ABS_RX, ABS_RY};

    // Android handhelds also use centered Z/RZ for the right stick.
    // Unipolar Z/RZ axes remain trigger inputs.
    if (cap.axis[ABS_Z] && cap.axis[ABS_RZ] &&
        cap.info[ABS_Z].minimum < 0 && cap.info[ABS_Z].maximum > 0 &&
        cap.info[ABS_RZ].minimum < 0 && cap.info[ABS_RZ].maximum > 0) {
        return {ABS_Z, ABS_RZ};
    }
    return {ABS_RX, ABS_RY};
}

int normalizeStick(const Capabilities& cap, const SourceFrame& frame, unsigned code) {
    if (!cap.axis[code]) return 0;
    const auto& info = cap.info[code];
    const auto center = neutralAxis(code, info);
    const auto delta = static_cast<long long>(frame.axes[code]) - center;
    const auto span = delta < 0 ? static_cast<long long>(center) - info.minimum :
        static_cast<long long>(info.maximum) - center;
    if (span <= 0 || std::abs(delta) <= info.flat) return 0;
    return static_cast<int>(std::clamp(delta * 1000 / span, -1000LL, 1000LL));
}

bool writeReport(int fd, const input_event* events, size_t count) {
    const auto bytes = count * sizeof(*events);
    ssize_t sent;
    do {
        sent = write(fd, events, bytes);
    } while (sent < 0 && errno == EINTR);
    return sent == static_cast<ssize_t>(bytes);
}

} // namespace

bool isVirtualController(const std::string& name) { return name == kVirtualName; }
const char* virtualInputPort() { return kVirtualPort; }

std::vector<Controller> scanControllers() {
    std::unique_ptr<DIR, decltype(&closedir)> dir(opendir("/dev/input"), closedir);
    if (!dir) kernelError("List /dev/input (verify Magisk access)");

    std::vector<Controller> controllers;
    int accessError = 0;
    for (;;) {
        errno = 0;
        const auto* entry = readdir(dir.get());
        if (!entry) {
            if (errno) kernelError("Read /dev/input");
            break;
        }
        const std::string name(entry->d_name);
        if (name.size() <= 5 || name.compare(0, 5, "event") != 0) continue;
        int node = -1;
        const auto parsed = std::from_chars(name.data() + 5, name.data() + name.size(), node);
        if (parsed.ec != std::errc{} || parsed.ptr != name.data() + name.size() || node < 0) continue;

        auto fd = openSource(node);
        if (!fd) {
            if (errno == EACCES || errno == EPERM) accessError = errno;
            continue;
        }
        try {
            auto label = controllerName(fd.get());
            if (isVirtualController(label)) continue;
            const auto cap = describe(fd.get());
            if (cap.supported) {
                controllers.push_back({node, identity(fd.get()), hardwareIdentity(fd.get()), std::move(label), cap.supported});
            }
        } catch (const std::system_error& error) {
            if (error.code().value() != ENODEV && error.code().value() != ENOENT) throw;
        }
    }
    if (controllers.empty() && accessError) kernelError("Read input device nodes", accessError);
    std::sort(controllers.begin(), controllers.end(), [](const auto& lhs, const auto& rhs) {
        return lhs.node < rhs.node;
    });
    return controllers;
}

class Session::Engine {
public:
    Engine(int node, const std::string& expected, int rate)
        : source_(openSource(node)), capabilities_(validate(expected)),
          wake_(eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC)),
          modes_(capabilities_.supported) {
        if (!wake_) kernelError("Create session wake event");
        requireIoctl(source_.get(), EVIOCGRAB, 1, "Take exclusive control of the physical gamepad");
        visibility_ = std::make_unique<SourceVisibility>(source_.get(), node);
        sink_ = createVirtual(capabilities_);
        if (!sample(source_.get(), capabilities_, raw_)) kernelError("Read initial gamepad state");
        modes_.physicalFrame(raw_.buttons());
        modes_.setRate(rate, Clock::now());
        status_ = Status::Active;
        worker_ = std::thread([this] { run(); });
    }

    ~Engine() { stop(); }

    void stop() {
        quit_ = true;
        wake();
        if (worker_.joinable()) worker_.join();
    }

    void setRate(int rate) {
        std::lock_guard lock(mutex_);
        modes_.setRate(rate, Clock::now());
        wake();
    }

    void cycle(Button button) {
        std::lock_guard lock(mutex_);
        modes_.cycle(bit(button), Clock::now());
        wake();
    }

    void clear() {
        std::lock_guard lock(mutex_);
        modes_.clear();
        wake();
    }

    Snapshot snapshot() const {
        std::lock_guard lock(mutex_);
        return {status_, modes_.physical(), sentButtons_, modes_.held(),
                modes_.turbo(), sticks_};
    }

private:
    Capabilities validate(const std::string& expected) {
        if (!source_) kernelError("Open the selected gamepad");
        if (identity(source_.get()) != expected || isVirtualController(controllerName(source_.get()))) {
            throw std::runtime_error("The selected controller changed. Select it again.");
        }
        const auto cap = describe(source_.get());
        if (!cap.supported) throw std::runtime_error("The selected input is not a supported gamepad.");
        return cap;
    }

    void wake() const {
        const std::uint64_t value = 1;
        while (write(wake_.get(), &value, sizeof(value)) < 0 && errno == EINTR) {}
    }

    bool publish(Clock::time_point now, bool release = false) {
        const auto buttons = release ? Buttons{0} : modes_.output(now);
        std::array<input_event, ButtonCount + KEY_CNT + kAxes.size() + 1> events{};
        size_t count = 0;
        const auto append = [&](unsigned type, unsigned code, int value) {
            events[count].type = static_cast<__u16>(type);
            events[count].code = static_cast<__u16>(code);
            events[count++].value = value;
        };
        const auto changed = initialized_ ? sentButtons_ ^ buttons : capabilities_.digital;
        for (unsigned index = 0; index < kKeyCodes.size(); ++index) {
            if (index >= Up && index <= Right) continue;
            const auto flag = Buttons{1} << index;
            if (changed & flag) append(EV_KEY, kKeyCodes[index], (buttons & flag) != 0);
        }

        auto nextExtraKeys = sentExtraKeys_;
        for (const auto code : capabilities_.extraKeys) {
            const bool down = !release && raw_.extraKeys[code];
            if (!initialized_ || sentExtraKeys_[code] != down) append(EV_KEY, code, down);
            nextExtraKeys[code] = down;
        }
        auto nextAxes = sentAxes_;
        const bool neutral = release;
        for (const auto code : kAxes) {
            if (!virtualAxis(capabilities_, code)) continue;
            int value;
            if (code == ABS_HAT0X) {
                value = !!(buttons & bit(Right)) - !!(buttons & bit(Left));
            } else if (code == ABS_HAT0Y) {
                value = !!(buttons & bit(Down)) - !!(buttons & bit(Up));
            } else if (neutral) {
                value = neutralAxis(code, capabilities_.info[code]);
            } else {
                value = raw_.axes[code];
            }
            if (!initialized_ || sentAxes_[code] != value) {
                append(EV_ABS, code, value);
                nextAxes[code] = value;
            }
        }

        if (count) {
            append(EV_SYN, SYN_REPORT, 0);
            if (!writeReport(sink_.get(), events.data(), count)) return false;
        }
        initialized_ = true;
        sentButtons_ = buttons;
        sentAxes_ = nextAxes;
        sentExtraKeys_ = nextExtraKeys;
        return true;
    }

    Status processInput() {
        std::array<input_event, 64> events{};
        const auto bytes = read(source_.get(), events.data(), sizeof(events));
        if (bytes < 0 && (errno == EAGAIN || errno == EINTR)) return Status::Active;
        if (!bytes || (bytes < 0 && (errno == ENODEV || errno == ENXIO))) {
            return Status::Disconnected;
        }
        if (bytes < 0 || bytes % sizeof(input_event)) return Status::ReadFailed;
        const auto count = static_cast<size_t>(bytes) / sizeof(input_event);
        for (size_t index = 0; index < count && !quit_; ++index) {
            const auto& event = events[index];
            if (event.type == EV_SYN && event.code == SYN_DROPPED) {
                dropped_ = true;
            } else if (event.type == EV_SYN && event.code == SYN_REPORT) {
                if (dropped_) {
                    if (!sample(source_.get(), capabilities_, raw_)) return Status::ReadFailed;
                    // The snapshot includes queued input; do not replay older frames.
                    if (!publishPhysical()) return Status::WriteFailed;
                    dropped_ = false;
                    return Status::Active;
                }
                if (!publishPhysical()) return Status::WriteFailed;
            } else if (!dropped_) {
                storeInput(event);
            }
        }
        return Status::Active;
    }

    bool publishPhysical() {
        std::lock_guard lock(mutex_);
        modes_.physicalFrame(raw_.buttons());
        const auto right = rightStickAxes(capabilities_);
        sticks_ = {normalizeStick(capabilities_, raw_, ABS_X),
                   normalizeStick(capabilities_, raw_, ABS_Y),
                   normalizeStick(capabilities_, raw_, right[0]),
                   normalizeStick(capabilities_, raw_, right[1])};
        return publish(Clock::now());
    }

    void storeInput(const input_event& event) {
        if (event.type == EV_KEY && (event.value == 0 || event.value == 1)) {
            const auto found = std::find(kKeyCodes.begin(), kKeyCodes.end(), event.code);
            if (found == kKeyCodes.end()) {
                if (event.code <= KEY_MAX) raw_.extraKeys[event.code] = event.value != 0;
                return;
            }
            const auto flag = Buttons{1} << (found - kKeyCodes.begin());
            if (event.value) raw_.digital |= flag;
            else raw_.digital &= ~flag;
        } else if (event.type == EV_ABS && event.code < ABS_CNT && capabilities_.axis[event.code]) {
            raw_.axes[event.code] = event.value;
        }
    }

    Status drive() {
        pollfd descriptors[] = {{source_.get(), POLLIN, 0}, {wake_.get(), POLLIN, 0},
                                {visibility_->guardFd(), POLLIN, 0}};
        while (!quit_) {
            int timeout = -1;
            {
                std::lock_guard lock(mutex_);
                const auto now = Clock::now();
                if (!publish(now)) return Status::WriteFailed;
                const auto deadline = modes_.nextEdge(now);
                if (deadline != Clock::time_point::max()) {
                    timeout = static_cast<int>(
                        std::chrono::ceil<std::chrono::milliseconds>(deadline - now).count());
                }
            }
            const auto ready = poll(descriptors, 3, timeout);
            if (ready < 0 && errno == EINTR) continue;
            if (ready < 0) return Status::ReadFailed;
            if (descriptors[1].revents & POLLIN) {
                std::uint64_t count;
                static_cast<void>(read(wake_.get(), &count, sizeof(count)));
            }
            if (quit_) break;
            if (descriptors[2].revents) return Status::GuardFailed;
            if (descriptors[0].revents & (POLLERR | POLLHUP | POLLNVAL)) return Status::Disconnected;
            if (descriptors[0].revents & POLLIN) {
                const auto status = processInput();
                if (status != Status::Active) return status;
            }
        }
        return Status::Idle;
    }

    void run() {
        const auto result = drive();
        std::lock_guard lock(mutex_);
        publish(Clock::now(), true);
        ioctl(sink_.get(), UI_DEV_DESTROY);
        sink_.reset();
        source_.reset();
        const bool restored = visibility_->restore();
        modes_.physicalFrame(0);
        sticks_ = {};
        sentButtons_ = 0;
        status_ = restored ? result : Status::RestoreFailed;
    }

    Fd source_;
    Capabilities capabilities_;
    Fd sink_;
    Fd wake_;
    std::unique_ptr<SourceVisibility> visibility_;
    mutable std::mutex mutex_;
    ButtonModes modes_;
    SourceFrame raw_;
    std::array<int, 4> sticks_{};
    std::array<int, ABS_CNT> sentAxes_{};
    std::array<bool, KEY_CNT> sentExtraKeys_{};
    Buttons sentButtons_ = 0;
    bool initialized_ = false;
    bool dropped_ = false;
    Status status_ = Status::Idle;
    std::atomic<bool> quit_{false};
    std::thread worker_;
};

Session::Session(int node, const std::string& identity, int rate)
    : engine_(std::make_unique<Engine>(node, identity, rate)) {}
Session::~Session() = default;
Status Session::stop() { engine_->stop(); return engine_->snapshot().status; }
void Session::setRate(int rate) { engine_->setRate(rate); }
void Session::cycle(Button button) { engine_->cycle(button); }
void Session::clear() { engine_->clear(); }
Snapshot Session::snapshot() const { return engine_->snapshot(); }

} // namespace thumbrest
