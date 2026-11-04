#pragma once

#include <string>

namespace zest {

inline void replace_all(std::string& value, const std::string& from, const std::string& to) {
    if (from.empty()) return;
    std::string::size_type pos = 0;
    while ((pos = value.find(from, pos)) != std::string::npos) {
        value.replace(pos, from.size(), to);
        pos += to.size();
    }
}

/** Donor-compatible servant name fixes applied to EntityScriptUtil strings. */
inline std::string replace_name(const std::string& in) {
    std::string out = in;
    replace_all(out, "Altria", "Artoria");
    replace_all(out, "Tam Lin", "Faerie Knight");
    return out;
}

}  // namespace zest
