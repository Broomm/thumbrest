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

    void setRate(int pressesPerSecond, Clock::time_point now) {
        if (pressesPerSecond < 1 || pressesPerSecond > 60) return;
        halfPeriod_ = std::chrono::nanoseconds(1'000'000'000 / (2 * pressesPerSecond));
        phaseStart_ = now;
    }

    // Hardware input always belongs to the game. Only screen taps select modes.
    void physicalFrame(Buttons physical) { physical_ = physical & supported_; }

    void cycle(Buttons buttons, Clock::time_point now) {
        buttons &= supported_;
        const auto normal = buttons & ~(held_ | turbo_);
        const auto held = buttons & held_;
        held_ = (held_ & ~buttons) | normal;
        turbo_ = (turbo_ & ~buttons) | held;
        if (held) phaseStart_ = now;
    }

    void clear() {
        held_ = 0;
        turbo_ = 0;
    }

    Buttons output(Clock::time_point now) const {
        const auto pulses = (now - phaseStart_) / halfPeriod_;
        return (physical_ & ~turbo_) | held_ | (pulses % 2 == 0 ? turbo_ : 0);
    }

    Clock::time_point nextEdge(Clock::time_point now) const {
        if (!turbo_) return Clock::time_point::max();
        return phaseStart_ + ((now - phaseStart_) / halfPeriod_ + 1) * halfPeriod_;
    }

    Buttons physical() const { return physical_; }
    Buttons held() const { return held_; }
    Buttons turbo() const { return turbo_; }

private:
    Buttons supported_;
    Buttons physical_ = 0;
    Buttons held_ = 0;
    Buttons turbo_ = 0;
    Clock::time_point phaseStart_{};
    std::chrono::nanoseconds halfPeriod_{33'333'333};
};

} // namespace thumbrest
