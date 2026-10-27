/*
 * sampinject.exe - SA-MP Mobile's built-in SA-MP starter.
 *
 * Does what the original samp.exe does when it connects to a server, so the
 * app doesn't depend on whatever samp.exe a modpack ships (some replace it
 * with their own launcher):
 *   1. start gta_sa.exe suspended with "-c -n <nick> -h <host> -p <port>"
 *   2. hook its entry point so the game's main thread loads samp.dll first
 *      (a remote thread at creation time breaks the graphics driver in Wine)
 *   3. resume the game and wait for it, printing what happened to the log
 *
 * Usage: sampinject.exe <gta exe> <samp dll> [game arguments...]
 *   e.g. sampinject.exe D:\gta_sa.exe D:\samp.dll -c -n Carl -h 1.2.3.4 -p 7777
 *
 * Build (32-bit, like GTA, no console window): i686-w64-mingw32-gcc -O2 -s -static -mwindows -o sampinject.exe sampinject.c
 */
#include <windows.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

/* to stderr and to OutputDebugString: without a console window, the debug
   string is what reliably reaches the app's crash log (Wine's seh channel) */
static void say(const char *fmt, ...) {
    char msg[1024];
    va_list ap;
    va_start(ap, fmt);
    int n = snprintf(msg, sizeof(msg), "[sampinject] ");
    vsnprintf(msg + n, sizeof(msg) - n, fmt, ap);
    va_end(ap);
    fprintf(stderr, "%s\n", msg);
    fflush(stderr);
    OutputDebugStringA(msg);
}

static const char *exitName(DWORD code) {
    switch (code) {
        case 0: return "normal exit";
        case 0xC0000005: return "ACCESS_VIOLATION (crash)";
        case 0xC000001D: return "ILLEGAL_INSTRUCTION (crash)";
        case 0xC0000094: return "INTEGER_DIVIDE_BY_ZERO (crash)";
        case 0xC00000FD: return "STACK_OVERFLOW (crash)";
        case 0xC0000135: return "DLL_NOT_FOUND";
        case 0xC0000139: return "ENTRYPOINT_NOT_FOUND";
        case 0xC0000142: return "DLL_INIT_FAILED";
        case 0xC0000409: return "STACK_BUFFER_OVERRUN (crash)";
        case 0x80000003: return "BREAKPOINT (crash)";
        default: return "";
    }
}

/* ------------------------------------------------------------ watchdog */

typedef struct { DWORD pid; int count; int gameWindow; } WindowScan;

static BOOL CALLBACK listChild(HWND hwnd, LPARAM lp) {
    (void)lp;
    char text[512] = "", cls[64] = "";
    GetWindowTextA(hwnd, text, sizeof(text));
    GetClassNameA(hwnd, cls, sizeof(cls));
    if (text[0]) say("      [%s] %s", cls, text);
    return TRUE;
}

/* every top-level window of the game, and the texts inside dialogs (message boxes) */
static BOOL CALLBACK listWindow(HWND hwnd, LPARAM lp) {
    WindowScan *scan = (WindowScan *)lp;
    DWORD pid = 0;
    GetWindowThreadProcessId(hwnd, &pid);
    if (pid != scan->pid) return TRUE;
    char title[256] = "", cls[64] = "";
    GetWindowTextA(hwnd, title, sizeof(title));
    GetClassNameA(hwnd, cls, sizeof(cls));
    RECT r = {0, 0, 0, 0};
    GetWindowRect(hwnd, &r);
    BOOL visible = IsWindowVisible(hwnd);
    say("   window \"%s\" class=%s %s %ldx%ld at %ld,%ld", title, cls, visible ? "visible" : "hidden",
        r.right - r.left, r.bottom - r.top, r.left, r.top);
    if (strcmp(cls, "#32770") == 0) EnumChildWindows(hwnd, listChild, 0);
    if (visible && strcmp(cls, "#32770") != 0 && r.right - r.left >= 320) scan->gameWindow = 1;
    scan->count++;
    return TRUE;
}

/* AddressOfEntryPoint + ImageBase from the PE header, or 0 */
static DWORD peEntryPoint(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return 0;
    DWORD peOffset = 0, sig = 0, aep = 0, imageBase = 0;
    int ok = fseek(f, 0x3C, SEEK_SET) == 0 && fread(&peOffset, 4, 1, f) == 1 &&
             fseek(f, (long)peOffset, SEEK_SET) == 0 && fread(&sig, 4, 1, f) == 1 && sig == 0x4550 &&
             fseek(f, (long)peOffset + 0x28, SEEK_SET) == 0 && fread(&aep, 4, 1, f) == 1 &&
             fseek(f, (long)peOffset + 0x34, SEEK_SET) == 0 && fread(&imageBase, 4, 1, f) == 1;
    fclose(f);
    return ok && aep ? imageBase + aep : 0;
}

int main(int argc, char **argv) {
    if (argc < 3) {
        say("usage: sampinject.exe <gta exe> <samp dll> [game arguments...]");
        return 2;
    }
    const char *exe = argv[1];
    const char *dll = argv[2];

    /* game folder = folder of the exe, with its trailing backslash ("D:\") */
    char dir[MAX_PATH];
    lstrcpynA(dir, exe, MAX_PATH);
    char *slash = strrchr(dir, '\\');
    if (slash) slash[1] = '\0';
    else lstrcpyA(dir, ".\\");

    /* command line: "<exe>" followed by the game arguments */
    char cmd[2048];
    snprintf(cmd, sizeof(cmd), "\"%s\"", exe);
    for (int i = 3; i < argc; i++) {
        size_t len = strlen(cmd);
        snprintf(cmd + len, sizeof(cmd) - len, " %s", argv[i]);
    }

    if (GetFileAttributesA(exe) == INVALID_FILE_ATTRIBUTES) {
        say("ERROR: %s not found", exe);
        return 3;
    }
    if (GetFileAttributesA(dll) == INVALID_FILE_ATTRIBUTES) {
        say("ERROR: %s not found", dll);
        return 3;
    }

    say("starting %s in %s", cmd, dir);
    STARTUPINFOA si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    ZeroMemory(&pi, sizeof(pi));
    if (!CreateProcessA(exe, cmd, NULL, NULL, FALSE, CREATE_SUSPENDED, NULL, dir, &si, &pi)) {
        say("ERROR: CreateProcess failed, error %lu", GetLastError());
        return 4;
    }
    say("gta_sa.exe started (pid %lu), loading %s", pi.dwProcessId, dll);

    /* Where the game starts: AddressOfEntryPoint from the exe's PE header
       (gta_sa.exe 1.0 has no ASLR, so ImageBase is where it is loaded). */
    DWORD entry = peEntryPoint(exe);
    if (!entry) {
        say("ERROR: could not read the entry point of %s", exe);
        TerminateProcess(pi.hProcess, 1);
        return 5;
    }

    /*
     * Load samp.dll from GTA's own main thread, as the first thing the game
     * runs, instead of from a remote thread while the process is frozen at
     * creation: under Wine, using user32 that early leaves the process
     * without a graphics driver ("no driver could be loaded") and GTA can't
     * open its window. The entry point gets a jump to this stub:
     *
     *   pushad / pushfd
     *   push dllPath ; mov eax, LoadLibraryA ; call eax ; mov [result], eax
     *   copy the 5 original entry bytes back
     *   push 5 ; push entry ; push -1 ; mov eax, FlushInstructionCache ; call eax
     *   popfd / popad
     *   jmp entry
     */
    HMODULE k32 = GetModuleHandleA("kernel32.dll");
    DWORD pLoadLibrary = (DWORD)(ULONG_PTR)GetProcAddress(k32, "LoadLibraryA");
    DWORD pFlush = (DWORD)(ULONG_PTR)GetProcAddress(k32, "FlushInstructionCache");

    unsigned char *remote = (unsigned char *)VirtualAllocEx(pi.hProcess, NULL, 4096, MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
    if (!remote) {
        say("ERROR: VirtualAllocEx failed, error %lu", GetLastError());
        TerminateProcess(pi.hProcess, 1);
        return 5;
    }
    DWORD base = (DWORD)(ULONG_PTR)remote;
    DWORD aResult = base + 0x100, aSaved = base + 0x108, aPath = base + 0x200;

    unsigned char saved[5];
    if (!ReadProcessMemory(pi.hProcess, (LPCVOID)(ULONG_PTR)entry, saved, 5, NULL)) {
        say("ERROR: could not read the game's entry point, error %lu", GetLastError());
        TerminateProcess(pi.hProcess, 1);
        return 5;
    }

    unsigned char page[4096];
    memset(page, 0x90, sizeof(page));
    unsigned char *c = page;
#define B(x) (*c++ = (unsigned char)(x))
#define D(x) do { DWORD v_ = (DWORD)(x); memcpy(c, &v_, 4); c += 4; } while (0)
    B(0x60); B(0x9C);                               /* pushad; pushfd */
    B(0x68); D(aPath);                              /* push dllPath */
    B(0xB8); D(pLoadLibrary);                       /* mov eax, LoadLibraryA */
    B(0xFF); B(0xD0);                               /* call eax */
    B(0xA3); D(aResult);                            /* mov [result], eax */
    B(0xBE); D(aSaved);                             /* mov esi, saved */
    B(0xBF); D(entry);                              /* mov edi, entry */
    B(0xB9); D(5);                                  /* mov ecx, 5 */
    B(0xFC); B(0xF3); B(0xA4);                      /* cld; rep movsb */
    B(0x6A); B(0x05);                               /* push 5 */
    B(0x68); D(entry);                              /* push entry */
    B(0x6A); B(0xFF);                               /* push -1 (this process) */
    B(0xB8); D(pFlush);                             /* mov eax, FlushInstructionCache */
    B(0xFF); B(0xD0);                               /* call eax */
    B(0x9D); B(0x61);                               /* popfd; popad */
    B(0xE9); D(entry - (base + (DWORD)(c - page) + 4)); /* jmp entry */
#undef B
#undef D
    DWORD notRun = 0xFFFFFFFF;
    memcpy(page + 0x100, &notRun, 4);
    memcpy(page + 0x108, saved, 5);
    lstrcpynA((char *)page + 0x200, dll, 4096 - 0x200);

    if (!WriteProcessMemory(pi.hProcess, remote, page, sizeof(page), NULL)) {
        say("ERROR: could not write into the game process, error %lu", GetLastError());
        TerminateProcess(pi.hProcess, 1);
        return 5;
    }

    unsigned char jump[5];
    jump[0] = 0xE9;
    DWORD rel = base - (entry + 5);
    memcpy(jump + 1, &rel, 4);
    DWORD oldProtect = 0;
    VirtualProtectEx(pi.hProcess, (LPVOID)(ULONG_PTR)entry, 5, PAGE_EXECUTE_READWRITE, &oldProtect);
    if (!WriteProcessMemory(pi.hProcess, (LPVOID)(ULONG_PTR)entry, jump, 5, NULL)) {
        say("ERROR: could not hook the entry point, error %lu", GetLastError());
        TerminateProcess(pi.hProcess, 1);
        return 5;
    }
    FlushInstructionCache(pi.hProcess, (LPCVOID)(ULONG_PTR)entry, 5);

    say("entry point 0x%08lx hooked, starting the game (samp.dll loads on its main thread)", entry);
    ResumeThread(pi.hThread);

    /* report whether samp.dll loaded */
    DWORD result = 0xFFFFFFFF;
    for (int i = 0; i < 1200; i++) {
        if (WaitForSingleObject(pi.hProcess, 100) == WAIT_OBJECT_0) break;
        if (ReadProcessMemory(pi.hProcess, (LPCVOID)(ULONG_PTR)aResult, &result, 4, NULL) && result != 0xFFFFFFFF) break;
    }
    if (result == 0xFFFFFFFF) say("WARNING: the game never reached the samp.dll loader");
    else if (result == 0) say("ERROR: samp.dll did not load (LoadLibrary returned NULL). A DLL it needs may be missing.");
    else say("samp.dll loaded at 0x%08lx", result);

    /* watchdog: while the game has no visible window, report where its main
       thread is and which windows (message boxes too) it has */
    for (int report = 0; report < 8; report++) {
        if (WaitForSingleObject(pi.hProcess, report == 0 ? 15000 : 20000) == WAIT_OBJECT_0) break;
        WindowScan scan = {pi.dwProcessId, 0, 0};
        EnumWindows(listWindow, (LPARAM)&scan);
        if (scan.gameWindow) {
            say("the game window is open");
            break;
        }
        say("no game window yet (check %d), %d top-level window(s) of gta_sa.exe listed above", report + 1, scan.count);
        /* Do NOT suspend or read the game's thread here: on Winlator's Wine 9.2
           (experimental WoW64) + Box64 that crashed the wineserver and froze everything. */
    }
    CloseHandle(pi.hThread);

    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD code = 0;
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hProcess);
    say("gta_sa.exe exited with code 0x%08lx %s", code, exitName(code));
    return (int)code;
}
