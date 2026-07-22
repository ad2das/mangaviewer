#pragma once
#include <Windows.h>
#include <TlHelp32.h>
#include <cstdint>
#include <cmath>
#include <vector>
#include <string>

#pragma region MEMORY CORE

struct ProcessHandle {
    DWORD pid = 0;
    HANDLE hProc = nullptr;
    uintptr_t moduleBase = 0;

    bool Attach(const wchar_t* procName) {
        HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if (snap == INVALID_HANDLE_VALUE) return false;

        PROCESSENTRY32W pe{ sizeof(pe) };
        bool found = false;
        if (Process32FirstW(snap, &pe)) {
            do {
                if (_wcsicmp(pe.szExeFile, procName) == 0) {
                    pid = pe.th32ProcessID;
                    found = true;
                    break;
                }
            } while (Process32NextW(snap, &pe));
        }
        CloseHandle(snap);
        if (!found) return false;

        hProc = OpenProcess(PROCESS_VM_READ | PROCESS_VM_WRITE | PROCESS_VM_OPERATION | PROCESS_QUERY_INFORMATION, FALSE, pid);
        if (!hProc) return false;

        moduleBase = GetModuleBase(procName);
        return moduleBase != 0;
    }

    uintptr_t GetModuleBase(const wchar_t* modName) {
        HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, pid);
        if (snap == INVALID_HANDLE_VALUE) return 0;

        MODULEENTRY32W me{ sizeof(me) };
        uintptr_t base = 0;
        if (Module32FirstW(snap, &me)) {
            do {
                if (_wcsicmp(me.szModule, modName) == 0) {
                    base = (uintptr_t)me.modBaseAddr;
                    break;
                }
            } while (Module32NextW(snap, &me));
        }
        CloseHandle(snap);
        return base;
    }

    template<typename T>
    T Read(uintptr_t addr) {
        T value{};
        if (hProc) ReadProcessMemory(hProc, (LPCVOID)addr, &value, sizeof(T), nullptr);
        return value;
    }

    template<typename T>
    void Write(uintptr_t addr, T value) {
        if (!hProc) return;
        DWORD old;
        VirtualProtectEx(hProc, (LPVOID)addr, sizeof(T), PAGE_EXECUTE_READWRITE, &old);
        WriteProcessMemory(hProc, (LPVOID)addr, &value, sizeof(T), nullptr);
        VirtualProtectEx(hProc, (LPVOID)addr, sizeof(T), old, &old);
    }

    uintptr_t ReadPtr(uintptr_t addr) {
        return Read<uintptr_t>(addr);
    }

    uintptr_t ResolvePointerChain(std::vector<uintptr_t> offsets) {
        uintptr_t addr = moduleBase + offsets[0];
        for (size_t i = 1; i < offsets.size(); ++i) {
            addr = ReadPtr(addr);
            if (!addr) return 0;
            addr += offsets[i];
        }
        return addr;
    }

    void Detour(uintptr_t addr, void* hook, size_t len = 5) {
        DWORD old;
        VirtualProtectEx(hProc, (LPVOID)addr, len, PAGE_EXECUTE_READWRITE, &old);
        BYTE rel32[5];
        rel32[0] = 0xE9;
        uintptr_t jmpDest = (uintptr_t)hook - addr - 5;
        *(uintptr_t*)(rel32 + 1) = jmpDest;
        WriteProcessMemory(hProc, (LPVOID)addr, rel32, 5, nullptr);
        VirtualProtectEx(hProc, (LPVOID)addr, len, old, &old);
    }

    ~ProcessHandle() {
        if (hProc) CloseHandle(hProc);
    }
};

#pragma endregion

#pragma region VECTOR MATH

struct Vec3 {
    float x, y, z;

    Vec3 operator-(const Vec3& o) const { return { x - o.x, y - o.y, z - o.z }; }
    Vec3 operator+(const Vec3& o) const { return { x + o.x, y + o.y, z + o.z }; }
    Vec3 operator*(float s) const { return { x * s, y * s, z * s }; }

    float Length() const { return sqrtf(x * x + y * y + z * z); }
    float Length2D() const { return sqrtf(x * x + z * z); }

    Vec3 Normalized() const {
        float l = Length();
        return l > 0.0001f ? Vec3{ x / l, y / l, z / l } : Vec3{ 0,0,0 };
    }

    float Dot(const Vec3& o) const { return x * o.x + y * o.y + z * o.z; }
};

float DegToRad(float d) { return d * 3.14159265f / 180.0f; }
float RadToDeg(float r) { return r * 180.0f / 3.14159265f; }

float CalcLaunchAngle(float distance, float heightDiff, float speed, float g = 9.81f) {
    // returns pitch angle (radians) for projectile to hit target
    float v2 = speed * speed;
    float root = v2 * v2 - g * (g * distance * distance + 2.0f * heightDiff * v2);
    if (root < 0) return DegToRad(45.0f);
    float tanA = (v2 - sqrtf(root)) / (g * distance);
    return atanf(tanA);
}

float CalcYawToTarget(const Vec3& from, const Vec3& to) {
    float dx = to.x - from.x;
    float dz = to.z - from.z;
    return atan2f(dx, dz);
}

#pragma endregion

#pragma region GAME ENTITY

struct PlayerEntity {
    uintptr_t base = 0;
    Vec3 pos{};
    Vec3 vel{};
    float facingYaw = 0;
    int team = 0;
    bool isHoldingBall = false;
    float shotMeter = 0.0f;
    float speed = 5.5f;
    int stamina = 100;
    bool isMyPlayer = false;
};

struct BallEntity {
    Vec3 pos{};
    Vec3 vel{};
    Vec3 predictedLanding{};
    bool inFlight = false;
    bool isLoose = false;
    uintptr_t holder = 0;
};

struct HoopEntity {
    Vec3 pos{};
    float radius = 0.23f;
    float height = 3.05f;
};

#pragma endregion

#pragma region HACK MODULES

class AimbotShot {
public:
    bool enabled = true;
    bool perfectRelease = true;
    float maxDistance = 12.0f;
    float aimSmoothing = 0.35f;

    void Execute(ProcessHandle& proc, PlayerEntity& shooter, const HoopEntity& hoop) {
        if (!enabled) return;

        Vec3 toHoop = hoop.pos - shooter.pos;
        float dist = toHoop.Length2D();
        if (dist > maxDistance) return;

        float targetYaw = CalcYawToTarget(shooter.pos, hoop.pos);
        float heightDiff = hoop.height - shooter.pos.y;
        float speed = 8.5f + dist * 0.18f;
        float pitch = CalcLaunchAngle(dist, heightDiff, speed);

        // smooth aim toward hoop
        float cur = shooter.facingYaw;
        float diff = targetYaw - cur;
        while (diff > 3.14159f) diff -= 6.28318f;
        while (diff < -3.14159f) diff += 6.28318f;
        float newYaw = cur + diff * aimSmoothing;

        uintptr_t yawAddr = shooter.base + 0x1A4;
        proc.Write<float>(yawAddr, newYaw);

        if (perfectRelease) {
            uintptr_t meterAddr = shooter.base + 0x2D8;
            proc.Write<float>(meterAddr, 1.0f);
        }
    }
};

class SpeedHack {
public:
    bool enabled = false;
    float multiplier = 1.6f;
    uintptr_t speedOffset = 0x1C8;

    void Execute(ProcessHandle& proc, PlayerEntity& p) {
        if (!enabled) return;
        float target = p.speed * multiplier;
        proc.Write<float>(p.base + speedOffset, target);
    }

    void Disable(ProcessHandle& proc, PlayerEntity& p) {
        proc.Write<float>(p.base + speedOffset, p.speed);
    }
};

class ReboundAssist {
public:
    bool enabled = true;
    float triggerHeight = 3.4f;
    float moveToBallSpeed = 1.4f;

    bool ShouldJump(const BallEntity& ball, const PlayerEntity& me) {
        if (!enabled || !ball.inFlight) return false;
        float heightDiff = ball.predictedLanding.y - me.pos.y;
        if (heightDiff > triggerHeight) return false;
        float horizDist = (ball.predictedLanding - me.pos).Length2D();
        return horizDist < 2.5f;
    }

    void MoveToLandingSpot(ProcessHandle& proc, PlayerEntity& me, const BallEntity& ball) {
        if (!enabled || !ball.inFlight) return;
        Vec3 dir = (ball.predictedLanding - me.pos);
        dir.y = 0;
        dir = dir.Normalized();
        Vec3 move = dir * moveToBallSpeed;
        Vec3 newPos = me.pos + move;
        proc.Write<Vec3>(me.base + 0x10C, newPos);
    }

    void ForceJump(ProcessHandle& proc, PlayerEntity& me) {
        uintptr_t jumpFlag = me.base + 0x318;
        proc.Write<int>(jumpFlag, 1);
    }
};

class StealAssist {
public:
    bool enabled = true;
    float range = 2.2f;
    int cooldownMs = 800;
    DWORD lastSteal = 0;

    void TrySteal(ProcessHandle& proc, PlayerEntity& me, PlayerEntity& carrier) {
        if (!enabled || !carrier.isHoldingBall) return;
        DWORD now = GetTickCount();
        if (now - lastSteal < cooldownMs) return;

        float dist = (carrier.pos - me.pos).Length2D();
        if (dist <= range) {
            uintptr_t stealBtn = me.base + 0x320;
            proc.Write<int>(stealBtn, 1);
            lastSteal = now;
        }
    }
};

class StaminaLock {
public:
    bool enabled = true;
    int maxValue = 100;

    void Execute(ProcessHandle& proc, PlayerEntity& p) {
        if (!enabled) return;
        proc.Write<int>(p.base + 0x2E0, maxValue);
    }
};

class PerfectBlock {
public:
    bool enabled = true;
    float blockRange = 2.8f;

    void TryBlock(ProcessHandle& proc, PlayerEntity& me, PlayerEntity& shooter) {
        if (!enabled) return;
        float dist = (shooter.pos - me.pos).Length2D();
        if (dist <= blockRange) {
            uintptr_t blockFlag = me.base + 0x328;
            proc.Write<int>(blockFlag, 1);
        }
    }
};

#pragma endregion

#pragma region OVERLAY DRAWING

class Overlay {
    HWND targetHwnd = nullptr;
    HWND overlayHwnd = nullptr;
    HDC hdc = nullptr;
    int w = 0, h = 0;

    static LRESULT CALLBACK WndProc(HWND h, UINT msg, WPARAM wp, LPARAM lp) {
        return DefWindowProcW(h, msg, wp, lp);
    }

public:
    bool Init(const wchar_t* windowTitle) {
        targetHwnd = FindWindowW(nullptr, windowTitle);
        if (!targetHwnd) return false;

        RECT rc;
        GetWindowRect(targetHwnd, &rc);
        w = rc.right - rc.left;
        h = rc.bottom - rc.top;

        WNDCLASSW wc{};
        wc.lpfnWndProc = WndProc;
        wc.hInstance = GetModuleHandleW(nullptr);
        wc.lpszClassName = L"FsOverlay";
        RegisterClassW(&wc);

        overlayHwnd = CreateWindowExW(
            WS_EX_TOPMOST | WS_EX_TRANSPARENT | WS_EX_LAYERED | WS_EX_NOACTIVATE,
            L"FsOverlay", L"FS", WS_POPUP,
            rc.left, rc.top, w, h,
            nullptr, nullptr, wc.hInstance, nullptr);

        SetLayeredWindowAttributes(overlayHwnd, RGB(0, 0, 0), 0, LWA_COLORKEY);
        ShowWindow(overlayHwnd, SW_SHOWNORMAL);
        return true;
    }

    void Begin() {
        hdc = GetDC(overlayHwnd);
    }

    void End() {
        ReleaseDC(overlayHwnd, hdc);
    }

    void Text(int x, int y, const wchar_t* str, COLORREF color = RGB(0, 255, 100)) {
        SetTextColor(hdc, color);
        SetBkMode(hdc, TRANSPARENT);
        TextOutW(hdc, x, y, str, (int)wcslen(str));
    }

    void Line(int x1, int y1, int x2, int y2, COLORREF color = RGB(255, 50, 50)) {
        HPEN pen = CreatePen(PS_SOLID, 2, color);
        SelectObject(hdc, pen);
        MoveToEx(hdc, x1, y1, nullptr);
        LineTo(hdc, x2, y2);
        DeleteObject(pen);
    }

    void Box(int x, int y, int bw, int bh, COLORREF color = RGB(0, 200, 255)) {
        HPEN pen = CreatePen(PS_SOLID, 1, color);
        SelectObject(hdc, pen);
        HBRUSH br = (HBRUSH)GetStockObject(NULL_BRUSH);
        SelectObject(hdc, br);
        Rectangle(hdc, x, y, x + bw, y + bh);
        DeleteObject(pen);
    }
};

#pragma endregion

#pragma region MAIN TRAINER

class FreestyleTrainer {
public:
    ProcessHandle proc;
    AimbotShot aimbot;
    SpeedHack speed;
    ReboundAssist rebound;
    StealAssist steal;
    StaminaLock stamina;
    PerfectBlock blocker;
    Overlay overlay;

    bool godMode = false;
    bool showESP = true;

    PlayerEntity myPlayer;
    PlayerEntity carrier;
    BallEntity ball;
    HoopEntity enemyHoop;

    bool Init() {
        // Freestyle2 process name — adjust if needed
        if (!proc.Attach(L"Freestyle2.exe")) return false;
        if (!overlay.Init(L"Freestyle2")) return false;
        return true;
    }

    void UpdateEntities() {
        // example offset chains — RE these from the actual game
        // these are placeholder patterns showing the structure
        uintptr_t localPtr = proc.ReadPtr(proc.moduleBase + 0x4A2B10);
        if (localPtr) {
            myPlayer.base = localPtr;
            myPlayer.pos = proc.Read<Vec3>(localPtr + 0x10C);
            myPlayer.vel = proc.Read<Vec3>(localPtr + 0x118);
            myPlayer.facingYaw = proc.Read<float>(localPtr + 0x1A4);
            myPlayer.isHoldingBall = proc.Read<int>(localPtr + 0x2F0) == 1;
            myPlayer.isMyPlayer = true;
        }

        uintptr_t ballPtr = proc.ReadPtr(proc.moduleBase + 0x4A2B40);
        if (ballPtr) {
            ball.pos = proc.Read<Vec3>(ballPtr + 0x10);
            ball.vel = proc.Read<Vec3>(ballPtr + 0x1C);
            ball.inFlight = proc.Read<int>(ballPtr + 0x40) == 1;
            ball.isLoose = proc.Read<int>(ballPtr + 0x44) == 1;
            if (ball.inFlight) {
                PredictBallLanding();
            }
        }

        uintptr_t hoopPtr = proc.ReadPtr(proc.moduleBase + 0x4A2C80);
        if (hoopPtr) {
            enemyHoop.pos = proc.Read<Vec3>(hoopPtr + 0x10);
            enemyHoop.height = proc.Read<float>(hoopPtr + 0x24);
        }
    }

    void PredictBallLanding() {
        Vec3 p = ball.pos;
        Vec3 v = ball.vel;
        float g = 9.81f;
        // solve quadratic for y returning to hoop height
        float targetY = enemyHoop.height;
        float a = -0.5f * g;
        float b = v.y;
        float c = p.y - targetY;
        float disc = b * b - 4 * a * c;
        if (disc < 0) return;
        float t = (-b + sqrtf(disc)) / (2 * a);
        if (t < 0) t = (-b - sqrtf(disc)) / (2 * a);
        ball.predictedLanding.x = p.x + v.x * t;
        ball.predictedLanding.y = targetY;
        ball.predictedLanding.z = p.z + v.z * t;
    }

    void RunLoop() {
        while (true) {
            if (GetAsyncKeyState(VK_END) & 1) break;

            UpdateEntities();
            if (!myPlayer.base) { Sleep(50); continue; }

            // hotkeys
            if (GetAsyncKeyState(VK_F1) & 1) aimbot.enabled = !aimbot.enabled;
            if (GetAsyncKeyState(VK_F2) & 1) speed.enabled = !speed.enabled;
            if (GetAsyncKeyState(VK_F3) & 1) rebound.enabled = !rebound.enabled;
            if (GetAsyncKeyState(VK_F4) & 1) steal.enabled = !steal.enabled;
            if (GetAsyncKeyState(VK_F5) & 1) stamina.enabled = !stamina.enabled;
            if (GetAsyncKeyState(VK_F6) & 1) blocker.enabled = !blocker.enabled;
            if (GetAsyncKeyState(VK_F7) & 1) godMode = !godMode;

            // execute modules
            aimbot.Execute(proc, myPlayer, enemyHoop);
            speed.Execute(proc, myPlayer);
            stamina.Execute(proc, myPlayer);

            if (rebound.ShouldJump(ball, myPlayer)) {
                rebound.ForceJump(proc, myPlayer);
            } else {
                rebound.MoveToLandingSpot(proc, myPlayer, ball);
            }

            steal.TrySteal(proc, myPlayer, carrier);
            blocker.TryBlock(proc, myPlayer, carrier);

            if (godMode) {
                proc.Write<int>(myPlayer.base + 0x2E0, 100);
                proc.Write<int>(myPlayer.base + 0x2F8, 1);
            }

            DrawOverlay();
            Sleep(8);
        }
    }

    void DrawOverlay() {
        overlay.Begin();
        overlay.Text(20, 20, L"[ Freestyle Trainer — Nyx ]");
        wchar_t buf[256];
        swprintf_s(buf, L"[F1] Aimbot: %s   [F2] Speed: %s   [F3] Rebound: %s",
            aimbot.enabled ? L"ON" : L"OFF",
            speed.enabled ? L"ON" : L"OFF",
            rebound.enabled ? L"ON" : L"OFF");
        overlay.Text(20, 45, buf);
        swprintf_s(buf, L"[F4] Steal: %s   [F5] Stamina: %s   [F6] Block: %s   [F7] God: %s",
            steal.enabled ? L"ON" : L"OFF",
            stamina.enabled ? L"ON" : L"OFF",
            blocker.enabled ? L"ON" : L"OFF",
            godMode ? L"ON" : L"OFF");
        overlay.Text(20, 70, buf);
        overlay.Text(20, 95, L"[END] Exit");

        if (showESP && myPlayer.base) {
            int cx = w / 2, cy = h / 2;
            overlay.Line(cx - 10, cy, cx + 10, cy, RGB(0, 255, 100));
            overlay.Line(cx, cy - 10, cx, cy + 10, RGB(0, 255, 100));
            if (ball.inFlight) {
                overlay.Box(cx - 20, cy - 20, 40, 40, RGB(255, 200, 0));
            }
        }
        overlay.End();
    }
};

#pragma endregion
