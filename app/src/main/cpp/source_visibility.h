#pragma once

#include <memory>

namespace thumbrest {

// Hide one opened evdev node from Android's EventHub without losing its inode,
// permissions or SELinux label. A separate process restores it on writer death.
class SourceVisibility {
public:
    SourceVisibility(int source, int node);
    ~SourceVisibility();
    SourceVisibility(const SourceVisibility&) = delete;
    SourceVisibility& operator=(const SourceVisibility&) = delete;

    int guardFd() const;
    bool restore();

private:
    class State;
    std::unique_ptr<State> state_;
};

} // namespace thumbrest
