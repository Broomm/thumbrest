#include "linux_pad.h"

#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <signal.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <functional>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

using namespace thumbrest;
using namespace std::chrono_literals;

namespace {

class Descriptor {
public:
    explicit Descriptor(int value) : value_(value) {
        if (value_ < 0) throw std::runtime_error("Could not open test input device");
    }
    ~Descriptor() { reset(); }
    Descriptor(const Descriptor&) = delete;
    Descriptor& operator=(const Descriptor&) = delete;
    int get() const { return value_; }
    void reset() { if (value_ >= 0) { close(value_); value_ = -1; } }
private:
    int value_;
};

void require(bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error(message);
}

void waitFor(const std::function<bool()>& predicate, const std::string& message) {
    const auto deadline = Clock::now() + 2s;
    do {
        if (predicate()) return;
        std::this_thread::sleep_for(2ms);
    } while (Clock::now() < deadline);
    throw std::runtime_error(message);
}

void checkedIoctl(int fd, unsigned long request, unsigned long value) {
    require(ioctl(fd, request, value) >= 0, "Test input configuration failed");
}

struct FixturePad {
    Descriptor fd{open("/dev/uinput", O_WRONLY | O_CLOEXEC)};
    const std::string name = "Thumbrest device test " + std::to_string(getpid());
    unsigned rightX;
    unsigned rightY;
    bool triggerAxes;

    FixturePad(unsigned rx, unsigned ry, bool triggers = false)
        : rightX(rx), rightY(ry), triggerAxes(triggers) {
        checkedIoctl(fd.get(), UI_SET_EVBIT, EV_KEY);
        for (unsigned key : {BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST, BTN_TL, BTN_TR,
                             BTN_SELECT, BTN_START, BTN_THUMBL, BTN_THUMBR}) {
            checkedIoctl(fd.get(), UI_SET_KEYBIT, key);
        }
        for (unsigned code : std::array<unsigned, 8>{ABS_X, ABS_Y, rightX, rightY, ABS_GAS, ABS_BRAKE, ABS_HAT0X, ABS_HAT0Y}) {
            checkedIoctl(fd.get(), UI_SET_EVBIT, EV_ABS);
            checkedIoctl(fd.get(), UI_SET_ABSBIT, code);
            uinput_abs_setup axis{};
            axis.code = code;
            const bool trigger = code == ABS_GAS || code == ABS_BRAKE ||
                (triggerAxes && (code == rightX || code == rightY));
            axis.absinfo.minimum = trigger ? 0 : -32767;
            axis.absinfo.maximum = 32767;
            if (code == ABS_HAT0X || code == ABS_HAT0Y) {
                axis.absinfo.minimum = -1;
                axis.absinfo.maximum = 1;
            }
            checkedIoctl(fd.get(), UI_ABS_SETUP, reinterpret_cast<unsigned long>(&axis));
        }
        uinput_setup setup{};
        std::strncpy(setup.name, name.c_str(), sizeof(setup.name) - 1);
        setup.id.bustype = BUS_VIRTUAL;
        checkedIoctl(fd.get(), UI_DEV_SETUP, reinterpret_cast<unsigned long>(&setup));
        checkedIoctl(fd.get(), UI_DEV_CREATE, 0);
    }

    void event(unsigned type, unsigned code, int value) {
        input_event report[2]{};
        report[0].type = type;
        report[0].code = code;
        report[0].value = value;
        report[1].type = EV_SYN;
        report[1].code = SYN_REPORT;
        require(write(fd.get(), report, sizeof(report)) == static_cast<ssize_t>(sizeof(report)), "Writing test source failed");
    }
};

Controller discover(const std::string& name) {
    Controller result{};
    waitFor([&] {
        for (const auto& device : scanControllers()) {
            if (device.name == name) { result = device; return true; }
        }
        return false;
    }, "The fixture controller was not discovered");
    return result;
}

std::vector<std::string> virtualNodes() {
    std::vector<std::string> nodes;
    std::unique_ptr<DIR, decltype(&closedir)> directory(opendir("/dev/input"), closedir);
    require(bool(directory), "Read input directory");
    while (const auto* entry = readdir(directory.get())) {
        if (std::strncmp(entry->d_name, "event", 5)) continue;
        const std::string path = std::string("/dev/input/") + entry->d_name;
        int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;
        std::array<char, 128> name{};
        const auto result = ioctl(fd, EVIOCGNAME(name.size() - 1), name.data());
        close(fd);
        if (result >= 0 && isVirtualController(name.data())) nodes.push_back(path);
    }
    return nodes;
}

std::string newVirtual(const std::vector<std::string>& before) {
    std::string result;
    waitFor([&] {
        for (const auto& path : virtualNodes()) {
            if (std::find(before.begin(), before.end(), path) == before.end()) {
                result = path;
                return true;
            }
        }
        return false;
    }, "The virtual output device was not published");
    return result;
}

bool keyDown(int fd, unsigned key) {
    std::array<unsigned long, KEY_MAX / (8 * sizeof(unsigned long)) + 1> keys{};
    require(ioctl(fd, EVIOCGKEY(sizeof(keys)), keys.data()) >= 0, "Read virtual key state");
    return (keys[key / (8 * sizeof(unsigned long))] >> (key % (8 * sizeof(unsigned long)))) & 1;
}

int axisValue(int fd, unsigned axis) {
    input_absinfo info{};
    require(ioctl(fd, EVIOCGABS(axis), &info) >= 0, "Read virtual axis state");
    return info.value;
}

void lifecycleAndModes(unsigned rightX, unsigned rightY, bool triggers = false) {
    FixturePad source(rightX, rightY, triggers);
    const auto selected = discover(source.name);
    const auto baseline = virtualNodes();
    Descriptor competing(open(("/dev/input/event" + std::to_string(selected.node)).c_str(),
                              O_RDONLY | O_NONBLOCK | O_CLOEXEC));
    auto session = std::make_unique<Session>(selected.node, selected.identity, 20);
    session->setVisible(true);
    const auto outputPath = newVirtual(baseline);
    Descriptor output(open(outputPath.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC));
    require(ioctl(competing.get(), EVIOCGRAB, 1) < 0 && errno == EBUSY,
            "The controller session did not hold the production input grab");

    source.event(EV_KEY, BTN_SOUTH, 1);
    waitFor([&] { return session->snapshot().held == bit(A); }, "Physical edit press did not select Held");
    source.event(EV_KEY, BTN_SOUTH, 0);
    waitFor([&] { return session->snapshot().physical == 0; }, "Physical release was not observed");
    require(!keyDown(output.get(), BTN_SOUTH), "Edit mode leaked a game button");
    session->setEditing(false);
    waitFor([&] { return keyDown(output.get(), BTN_SOUTH); }, "Held output did not reach evdev");

    source.event(EV_KEY, BTN_EAST, 1);
    source.event(EV_ABS, ABS_X, 16384);
    source.event(EV_ABS, rightX, triggers ? 16384 : -16384);
    source.event(EV_ABS, rightY, 16384);
    waitFor([&] {
        return keyDown(output.get(), BTN_EAST) &&
               axisValue(output.get(), ABS_X) == 16384 &&
               axisValue(output.get(), rightX) == (triggers ? 16384 : -16384) &&
               axisValue(output.get(), rightY) == 16384;
    }, "Ordinary buttons or axes did not pass through beside the held button");
    require(keyDown(output.get(), BTN_SOUTH), "Ordinary input interrupted Held");
    const auto preview = session->snapshot();
    const bool previewMatched = triggers
        ? preview.sticks[2] == 0 && preview.sticks[3] == 0
        : preview.sticks[2] < -490 && preview.sticks[3] > 490;

    session->setEditing(true);
    waitFor([&] { return !keyDown(output.get(), BTN_SOUTH); }, "Editing did not release Held");
    session->cycle(A);
    session->setEditing(false);
    const auto until = Clock::now() + 350ms;
    unsigned down = 0;
    unsigned up = 0;
    while (Clock::now() < until) {
        pollfd ready{output.get(), POLLIN, 0};
        const auto readable = poll(&ready, 1, 20);
        if (readable <= 0) continue;
        std::array<input_event, 64> events{};
        const auto count = read(output.get(), events.data(), sizeof(events));
        if (count <= 0) continue;
        for (ssize_t index = 0; index < count / static_cast<ssize_t>(sizeof(input_event)); ++index) {
            const auto& event = events[index];
            if (event.type == EV_KEY && event.code == BTN_SOUTH) {
                if (event.value) ++down;
                else ++up;
            }
        }
    }
    require(down >= 3 && up >= 3, "Turbo did not emit alternating kernel key edges");
    std::cout << "Hold, turbo, and passthrough passed for axes " << rightX << "/" << rightY
              << " (" << down << " down, " << up << " up).\n";

    source.fd.reset();
    waitFor([&] { return session->snapshot().status == Status::Disconnected; },
            "Source removal did not end the controller session");
    waitFor([&] { return access(outputPath.c_str(), F_OK) < 0; },
            "Virtual output survived a source disconnect");
    session.reset();
    require(previewMatched, "Right-stick preview did not follow this controller's axes");
    std::cout << "Disconnect cleanup and right-stick preview passed.\n";
}

void stopAndIdentity() {
    FixturePad source(ABS_RX, ABS_RY);
    const auto selected = discover(source.name);
    bool rejected = false;
    try {
        Session wrong(selected.node, selected.identity + "-changed", 15);
    } catch (const std::runtime_error&) {
        rejected = true;
    }
    require(rejected, "A stale controller identity was accepted");
    const auto baseline = virtualNodes();
    auto session = std::make_unique<Session>(selected.node, selected.identity, 15);
    const auto outputPath = newVirtual(baseline);
    session->setVisible(true);
    session->cycle(A);
    session->setEditing(false);
    session.reset();
    waitFor([&] { return access(outputPath.c_str(), F_OK) < 0; }, "Stop left the virtual controller alive");
    Descriptor physical(open(("/dev/input/event" + std::to_string(selected.node)).c_str(),
                             O_RDONLY | O_NONBLOCK | O_CLOEXEC));
    require(ioctl(physical.get(), EVIOCGRAB, 1) == 0, "Stop failed to release the physical input grab");
    require(ioctl(physical.get(), EVIOCGRAB, 0) == 0, "Test input grab could not be released");
    std::cout << "Stop cleanup and stale-identity rejection passed.\n";
}

void killedWriterCleanup() {
    FixturePad source(ABS_RX, ABS_RY);
    const auto selected = discover(source.name);
    const auto baseline = virtualNodes();
    int completion[2];
    require(pipe(completion) == 0, "Create writer coordination pipe");
    Descriptor reader(completion[0]);
    Descriptor writer(completion[1]);
    const auto child = fork();
    require(child >= 0, "Fork the controller writer");
    if (!child) {
        reader.reset();
        try {
            Session session(selected.node, selected.identity, 15);
            session.setVisible(true);
            session.cycle(A);
            session.setEditing(false);
            waitFor([&] { return session.snapshot().output == bit(A); },
                    "The writer did not publish its held button");
            const char ready = 'R';
            if (write(writer.get(), &ready, 1) != 1) _exit(2);
            for (;;) pause();
        } catch (...) {
            _exit(3);
        }
    }

    struct ChildProcess {
        pid_t pid;
        ~ChildProcess() {
            if (pid > 0) {
                kill(pid, SIGKILL);
                waitpid(pid, nullptr, 0);
            }
        }
    } process{child};
    writer.reset();
    pollfd signal{reader.get(), POLLIN, 0};
    require(poll(&signal, 1, 2'000) == 1, "The controller writer did not start");
    char ready = 0;
    require(read(reader.get(), &ready, 1) == 1 && ready == 'R', "The controller writer failed");
    const auto outputPath = newVirtual(baseline);
    require(kill(child, SIGKILL) == 0, "Terminate the held-button writer");
    require(waitpid(child, nullptr, 0) == child, "Reap the held-button writer");
    process.pid = -1;
    waitFor([&] { return access(outputPath.c_str(), F_OK) < 0; },
            "The virtual gamepad survived a killed writer");
    Descriptor physical(open(("/dev/input/event" + std::to_string(selected.node)).c_str(),
                             O_RDONLY | O_NONBLOCK | O_CLOEXEC));
    require(ioctl(physical.get(), EVIOCGRAB, 1) == 0,
            "A killed writer left the physical controller grabbed");
    require(ioctl(physical.get(), EVIOCGRAB, 0) == 0, "Release the cleanup-check input grab");
    std::cout << "Killed-writer cleanup passed while a button was held.\n";
}

} // namespace

int main() {
    try {
        stopAndIdentity();
        lifecycleAndModes(ABS_RX, ABS_RY);
        lifecycleAndModes(ABS_Z, ABS_RZ);
        lifecycleAndModes(ABS_Z, ABS_RZ, true);
        killedWriterCleanup();
    } catch (const std::exception& error) {
        std::cerr << "Device check failed: " << error.what() << '\n';
        return 1;
    }
    std::cout << "All device session checks passed.\n";
}
