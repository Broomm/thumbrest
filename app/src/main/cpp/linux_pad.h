#pragma once

#include "button_modes.h"

#include <array>
#include <memory>
#include <string>
#include <vector>

namespace thumbrest {

struct Controller {
    int node;
    std::string identity;
    std::string stableId;
    std::string name;
    Buttons supported;
};

std::vector<Controller> scanControllers();
bool isVirtualController(const std::string& name);
const char* virtualInputPort();

enum class Status { Idle, Active, Disconnected, ReadFailed, WriteFailed, GuardFailed, RestoreFailed };

struct Snapshot {
    Status status = Status::Idle;
    Buttons physical = 0;
    Buttons output = 0;
    Buttons held = 0;
    Buttons turbo = 0;
    std::array<int, 4> sticks{};
};

class Session {
public:
    Session(int node, const std::string& identity, int rate);
    ~Session();

    Status stop();
    void setRate(int rate);
    void cycle(Button button);
    void clear();
    Snapshot snapshot() const;

private:
    class Engine;
    std::unique_ptr<Engine> engine_;
};

} // namespace thumbrest
