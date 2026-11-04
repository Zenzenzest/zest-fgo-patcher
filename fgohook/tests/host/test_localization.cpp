#include "localization.h"

#include <cassert>
#include <iostream>

int main() {
    assert(zest::replace_name("Altria") == "Artoria");
    assert(zest::replace_name("Tam Lin") == "Faerie Knight");
    assert(zest::replace_name("Mash") == "Mash");
    assert(zest::replace_name("") == "");

    // Surrounding text and the longer forms must survive.
    assert(zest::replace_name("Altria Pendragon") == "Artoria Pendragon");
    assert(zest::replace_name("Servant: Tam Lin Lancelot") ==
           "Servant: Faerie Knight Lancelot");
    assert(zest::replace_name("Altria, Tam Lin") == "Artoria, Faerie Knight");

    // UTF-8 text that is not part of a replacement is passed through unchanged.
    assert(zest::replace_name("アルトリア") == "アルトリア");

    std::cout << "localization tests passed\n";
    return 0;
}
