#include "source_visibility.h"

#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <memory>
#include <stdexcept>
#include <string>
#include <system_error>
#include <utility>

namespace thumbrest {
namespace {

class Descriptor {
public:
    explicit Descriptor(int value = -1) : value_(value) {}
    ~Descriptor() { reset(); }
    Descriptor(const Descriptor&) = delete;
    Descriptor& operator=(const Descriptor&) = delete;
    int get() const { return value_; }
    void set(int value) { reset(); value_ = value; }
    void reset() { if (value_ >= 0) close(std::exchange(value_, -1)); }
private:
    int value_;
};

[[noreturn]] void failed(const char* operation, int error = errno) {
    throw std::system_error(error, std::generic_category(), operation);
}

bool sameNode(const struct stat& a, const struct stat& b) {
    return a.st_dev == b.st_dev && a.st_ino == b.st_ino && a.st_rdev == b.st_rdev &&
        S_ISCHR(a.st_mode) && S_ISCHR(b.st_mode);
}

// These functions also run after fork from ART. They use only Linux system
// calls and prepared memory: no JNI, allocation, logging, locks or C++ cleanup.
int restoreNode(int source, int directory, int backup, const char* name, const struct stat& expected) {
    int version = 0;
    int result;
    do { result = ioctl(source, EVIOCGVERSION, &version); } while (result < 0 && errno == EINTR);
    if (result < 0) return errno == ENODEV || errno == ENXIO ? 0 : errno;

    // Release the inherited grab before Android reopens the restored node.
    do { result = ioctl(source, EVIOCGRAB, 0); } while (result < 0 && errno == EINTR);
    if (result < 0 && errno != EINVAL) return errno;
    struct stat current{};
    if (fstatat(directory, name, &current, AT_SYMLINK_NOFOLLOW) == 0) {
        return sameNode(current, expected) ? 0 : EEXIST;
    }
    if (errno != ENOENT) return errno;
    if (linkat(backup, "source", directory, name, 0) < 0) {
        const int error = errno;
        // The parent and guardian may both recover after a failed acknowledgement.
        if (error != EEXIST || fstatat(directory, name, &current, AT_SYMLINK_NOFOLLOW) < 0 ||
            !sameNode(current, expected)) return error;
    }
    if (fstatat(directory, name, &current, AT_SYMLINK_NOFOLLOW) < 0) return errno;
    return sameNode(current, expected) ? 0 : ESTALE;
}

ssize_t sendStatus(int channel, int value) {
    ssize_t sent;
    do { sent = send(channel, &value, sizeof(value), MSG_NOSIGNAL); } while (sent < 0 && errno == EINTR);
    return sent;
}

bool closeInheritedExcept(int source, int directory, int backup, int channel) {
    // Enumerate in the child with raw syscalls. Enumerating before fork would
    // miss descriptors concurrently opened by ART or Binder threads.
    const int listing = open("/proc/self/fd", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (listing < 0) return false;
    struct Entry {
        std::uint64_t inode;
        std::int64_t offset;
        unsigned short length;
        unsigned char type;
        char name[1];
    };
    alignas(Entry) char buffer[8192];
    for (;;) {
        long bytes;
        do { bytes = syscall(__NR_getdents64, listing, buffer, sizeof(buffer)); } while (bytes < 0 && errno == EINTR);
        if (bytes < 0) { close(listing); return false; }
        if (!bytes) { close(listing); return true; }
        for (long offset = 0; offset < bytes;) {
            const auto* entry = reinterpret_cast<const Entry*>(buffer + offset);
            if (entry->length < sizeof(Entry) || entry->length > bytes - offset) {
                close(listing);
                return false;
            }
            int fd = 0;
            bool digits = entry->name[0] != 0;
            for (size_t index = 0; index < entry->length - offsetof(Entry, name) && entry->name[index]; ++index) {
                const char ch = entry->name[index];
                if (ch < '0' || ch > '9' || fd > 100'000'000) { digits = false; break; }
                fd = fd * 10 + ch - '0';
            }
            if (digits && fd != listing && fd != source && fd != directory && fd != backup && fd != channel) close(fd);
            offset += entry->length;
        }
    }
}

[[noreturn]] void guard(int source, int directory, int backup, int channel,
                        const char* name, const char* backupPath, const struct stat& expected) {
    setsid(); // libsu may terminate its own process group when its client exits.
    prctl(PR_SET_NAME, "thumbrest-guard", 0, 0, 0);
    const bool isolated = closeInheritedExcept(source, directory, backup, channel);
    // In particular, the guardian owns no uinput fd. A dead writer must destroy
    // its output even if this recovery process remains alive.
    sendStatus(channel, isolated ? 0 : EIO);
    char command;
    ssize_t received;
    do { received = recv(channel, &command, 1, 0); } while (received < 0 && errno == EINTR);
    const int result = restoreNode(source, directory, backup, name, expected);
    if (!result) {
        unlinkat(backup, "source", 0);
        rmdir(backupPath);
    }
    sendStatus(channel, result);
    _exit(result ? 1 : 0);
}

int receiveStatus(int channel, int timeout) {
    pollfd descriptor{channel, POLLIN, 0};
    int ready;
    do { ready = poll(&descriptor, 1, timeout); } while (ready < 0 && errno == EINTR);
    if (ready <= 0) return ready == 0 ? ETIMEDOUT : errno;
    int result = EPIPE;
    ssize_t received;
    do { received = recv(channel, &result, sizeof(result), 0); } while (received < 0 && errno == EINTR);
    return received == static_cast<ssize_t>(sizeof(result)) ? result : EPIPE;
}

} // namespace

class SourceVisibility::State {
public:
    ~State() { restore(); }

    void hide(int openedSource, int node) {
        source_.set(fcntl(openedSource, F_DUPFD_CLOEXEC, 3));
        if (source_.get() < 0) failed("Keep physical controller for crash recovery");
        name_ = "event" + std::to_string(node);
        directory_.set(open("/dev/input", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW));
        if (directory_.get() < 0) failed("Open physical input directory");
        if (fstat(source_.get(), &expected_) < 0) failed("Read physical controller inode");
        verifyOriginal();
        std::array<char, 32> path{};
        const std::string pattern = "/dev/.thumbrest-XXXXXX";
        std::copy(pattern.begin(), pattern.end(), path.begin());
        if (!mkdtemp(path.data())) failed("Prepare physical controller recovery directory");
        backupPath_ = path.data();
        backup_.set(open(backupPath_.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW));
        if (backup_.get() < 0) failed("Open physical controller recovery directory");
        if (linkat(directory_.get(), name_.c_str(), backup_.get(), "source", 0) < 0) {
            failed("Preserve physical controller before hiding it");
        }
        struct stat saved{};
        if (fstatat(backup_.get(), "source", &saved, AT_SYMLINK_NOFOLLOW) < 0) failed("Verify saved controller node");
        if (!sameNode(saved, expected_)) throw std::runtime_error("Controller changed while preparing recovery. Select it again.");
        prepared_ = true;

        int channel[2];
        if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, channel) < 0) {
            failed("Create controller recovery channel");
        }
        channel_.set(channel[0]);
        Descriptor childChannel(channel[1]);
        const int source = source_.get(), directory = directory_.get(), backup = backup_.get();
        const char* name = name_.c_str();
        const char* backupPath = backupPath_.c_str();
        child_ = fork();
        if (child_ < 0) failed("Start physical controller recovery process");
        if (child_ == 0) {
            guard(source, directory, backup, childChannel.get(), name, backupPath, expected_);
        }
        childChannel.reset();
        const int status = receiveStatus(channel_.get(), 1'000);
        if (status) failed("Physical controller recovery process did not become ready", status);
        verifyOriginal();
        // Existing evdev descriptors keep working. EventHub observes IN_DELETE
        // and removes the physical entry; games see only the virtual controller.
        if (unlinkat(directory_.get(), name_.c_str(), 0) < 0) failed("Hide the original controller");
        hidden_ = true;
    }

    int guardFd() const { return channel_.get(); }

    bool restore() {
        if (finished_) return restored_;
        if (!prepared_) {
            if (backup_.get() >= 0) unlinkat(backup_.get(), "source", 0);
            if (!backupPath_.empty()) rmdir(backupPath_.c_str());
            finished_ = true;
            return true;
        }
        int result = 0;
        if (channel_.get() >= 0 && child_ > 0) {
            shutdown(channel_.get(), SHUT_WR);
            result = receiveStatus(channel_.get(), 1'000);
        } else if (hidden_) {
            result = EPIPE;
        }
        if (result || child_ <= 0) {
            result = restoreNode(source_.get(), directory_.get(), backup_.get(), name_.c_str(), expected_);
        }
        restored_ = result == 0;
        if (restored_) {
            unlinkat(backup_.get(), "source", 0);
            rmdir(backupPath_.c_str());
        }
        channel_.reset();
        if (child_ > 0) {
            int status;
            for (int attempt = 0; attempt < 25; ++attempt) {
                const auto waited = waitpid(child_, &status, WNOHANG);
                if (waited == child_ || (waited < 0 && errno != EINTR)) break;
                poll(nullptr, 0, 1);
            }
        }
        child_ = -1;
        if (restored_) source_.reset();
        finished_ = restored_;
        return restored_;
    }

private:
    void verifyOriginal() {
        struct stat current{};
        if (fstatat(directory_.get(), name_.c_str(), &current, AT_SYMLINK_NOFOLLOW) < 0) {
            failed("Verify physical controller before hiding it");
        }
        if (!sameNode(current, expected_)) throw std::runtime_error("Controller node changed. Select it again.");
    }

    Descriptor source_, directory_, backup_, channel_;
    std::string name_, backupPath_;
    struct stat expected_{};
    pid_t child_ = -1;
    bool prepared_ = false;
    bool hidden_ = false;
    bool finished_ = false;
    bool restored_ = true;
};

SourceVisibility::SourceVisibility(int source, int node) : state_(std::make_unique<State>()) {
    state_->hide(source, node);
}
SourceVisibility::~SourceVisibility() = default;
int SourceVisibility::guardFd() const { return state_->guardFd(); }
bool SourceVisibility::restore() { return state_->restore(); }

} // namespace thumbrest
