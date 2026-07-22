#include "FreestyleHack.h"

int main() {
    SetConsoleTitleW(L"Freestyle Trainer — Nyx");
    FreestyleTrainer trainer;

    if (!trainer.Init()) {
        MessageBoxW(nullptr, L"Freestyle2.exe not found or overlay failed.", L"Error", MB_ICONERROR);
        return 1;
    }

    trainer.RunLoop();
    return 0;
}
