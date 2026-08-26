#include "../BitmapReferenceLedger.h"

#include <cassert>

int main() {
    ntk::rolling::BitmapReferenceLedger<3> ledger;

    assert(ledger.retain(11));
    assert(ledger.retain(11));
    assert(ledger.retain(22));
    assert(ledger.references(11));
    assert(ledger.references(22));
    assert(!ledger.references(33));
    assert(ledger.activeIdentityCount() == 2);
    assert(ledger.totalReferenceCount() == 3);

    assert(ledger.release(11));
    assert(ledger.references(11));
    assert(ledger.release(11));
    assert(!ledger.references(11));
    assert(!ledger.release(11));

    assert(ledger.retain(33));
    assert(ledger.retain(44));
    assert(!ledger.retain(55));
    assert(ledger.activeIdentityCount() == 3);
    assert(ledger.release(22));
    assert(ledger.retain(55));
    assert(ledger.references(33));
    assert(ledger.references(44));
    assert(ledger.references(55));
    return 0;
}
