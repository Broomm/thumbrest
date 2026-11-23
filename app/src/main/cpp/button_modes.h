#pragma once

#include <chrono>
#include <cstdint>

namespace thumbrest {

using Clock = std::chrono::steady_clock;
using Buttons = std::uint32_t;

enum Button : unsigned {
    A, B, X, Y, L1, R1, L2, R2, L3, R3, Select, Start, Up, Down, Left, Right, Guide,
    ButtonCount,
};

constexpr Buttons bit(Button button) { return Buttons{1} << button; }

class ButtonModes {
public:
    explicit ButtonModes(Buttons supported) : supported_(supported) {}

    void setEditing(bool editing, Clock::time_point now) {
        if (editing_ == editing) return;
        editing_ = editing;
        if (!editing) {
            // A button still physically down after editing begins passing through on
            // its next press. An intentional hold or turbo starts immediately.
            suppressed_ = physical_;
            phaseStart_ = now;
        }
    }

    void setRate(int pressesPerSecond, Clock::time_point now) {
        if (pressesPerSecond < 1 || pressesPerSecond > 60) return;
        halfPeriod_ = std::chrono::nanoseconds(1'000'000'000 / (2 * pressesPerSecond));
        phaseStart_ = now;
    }

    void physicalFrame(Buttons physical, bool complete) {
        physical &= supported_;
        if (editing_ && complete) cycle(physical & ~physical_);
        suppressed_ &= physical;
        physical_ = physical;
    }

    void cycle(Buttons buttons) {
        if (!editing_) return;
        buttons &= supported_;
        const auto neutral = buttons & ~(held_ | turbo_);
        const auto held = buttons & held_;
        held_ = (held_ & ~buttons) | neutral;
        turbo_ = (turbo_ & ~buttons) | held;
    }

    void clear() {
        if (editing_) {
            held_ = 0;
            turbo_ = 0;
        }
    }

    Buttons output(Clock::time_point now) const {
        if (editing_) return 0;
        const auto pulses = (now - phaseStart_) / halfPeriod_;
        return (physical_ & ~(suppressed_ | turbo_)) | held_ |
            (pulses % 2 == 0 ? turbo_ : 0);
    }

    Clock::time_point nextEdge(Clock::time_point now) const {
        if (editing_ || !turbo_) return Clock::time_point::max();
        return phaseStart_ + ((now - phaseStart_) / halfPeriod_ + 1) * halfPeriod_;
    }

    bool editing() const { return editing_; }
    Buttons physical() const { return physical_; }
    Buttons held() const { return held_; }
    Buttons turbo() const { return turbo_; }

private:
    Buttons supported_;
    Buttons physical_ = 0;
    Buttons suppressed_ = 0;
    Buttons held_ = 0;
    Buttons turbo_ = 0;
    bool editing_ = true;
    Clock::time_point phaseStart_{};
    std::chrono::nanoseconds halfPeriod_{33'333'333};
};

} // namespace thumbrest
