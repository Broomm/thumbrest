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
    std::string name;
    Buttons supported;
};

std::vector<Controller> scanControllers();
bool isVirtualController(const std::string& name);

enum class Status { Idle, Active, Disconnected, ReadFailed, WriteFailed };

struct Snapshot {
    Status status = Status::Idle;
    bool editing = false;
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

    void setEditing(bool value);
    void setVisible(bool value);
    void setRate(int rate);
    void cycle(Button button);
    void clear();
    Snapshot snapshot() const;

private:
    class Engine;
    std::unique_ptr<Engine> engine_;
};

} // namespace thumbrest
