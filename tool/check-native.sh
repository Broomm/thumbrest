#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .tool-cache/checks
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Werror -Iapp/src/main/cpp \
    app/src/test/cpp/button_modes_test.cpp -o .tool-cache/checks/button-modes
.tool-cache/checks/button-modes
