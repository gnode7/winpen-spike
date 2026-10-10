/*
 * synthetic_pointer_probe.c
 *
 * Win32 synthetic pointer injection probe, built around InjectSyntheticPointerInput
 * (winuser.h, Windows 10 1809+, User32.dll).
 *
 * Purpose: resolve two open questions on a real machine (see README.md):
 *   Experiment A ("origin")  - virtual-screen coordinate origin: primary monitor
 *                              upper-left (0,0) vs bounding-box upper-left.
 *   Experiment B ("dpi")     - whether injected ptPixelLocation is interpreted in
 *                              the calling thread's DPI coordinate space
 *                              (physical pixels under PMv2, virtualized 96-DPI
 *                              under unaware), per the official rule
 *                              "Windows scales ... to the coordinate space of
 *                              the calling thread".
 * Plus reusable tool modes: click / stroke (official hover<->contact state machine).
 *
 * Official sources (archived in ../docs/msdoc_synthetic_pointer/):
 *   - InjectSyntheticPointerInput / CreateSyntheticPointerDevice (winuser.h)
 *   - POINTER_INFO / POINTER_TOUCH_INFO / POINTER_PEN_INFO / POINTER_TYPE_INFO
 *     (field-for-field identical struct definitions replicated below)
 *   - "The Virtual Screen" (GDI): primary monitor contains (0,0)
 *   - "High DPI Desktop Application Development": calling-thread coordinate space
 *   - InjectTouchInput Remarks: hover/contact state machine table
 *
 * All new APIs are resolved at runtime via GetProcAddress so the exe also starts
 * on systems older than 1809 (with a friendly error instead of a load failure).
 *
 * Build (MSVC):  cl /W4 /O2 synthetic_pointer_probe.c
 * Build (MinGW): gcc -O2 -o synthetic_pointer_probe.exe synthetic_pointer_probe.c -luser32
 *
 * Exit code 0 = all requested injections succeeded.
 */

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------------ */
/* Official constants (values verified against WinUser.h 10.0.28000)   */
/* ------------------------------------------------------------------ */

#define PT_POINTER   1
#define PT_TOUCH     2
#define PT_PEN       3
#define PT_MOUSE     4
#define PT_TOUCHPAD  5
#define MAX_TOUCH_COUNT 256

/* POINTER_FLAGS (inputmsg/pointer-flags-contants) */
#define PFLAG_INRANGE        0x00000002
#define PFLAG_INCONTACT      0x00000004
#define PFLAG_FIRSTBUTTON    0x00000010
#define PFLAG_CONFIDENCE     0x00004000
#define PFLAG_DOWN           0x00010000
#define PFLAG_UPDATE         0x00020000
#define PFLAG_UP             0x00040000

/* TOUCH_MASK (inputmsg/touch-mask-constants) */
#define TMASK_NONE        0x00000000
#define TMASK_CONTACTAREA 0x00000001
#define TMASK_ORIENTATION 0x00000002
#define TMASK_PRESSURE    0x00000004

/* PEN_MASK (inputmsg/pen-mask-constants) */
#define PMASK_NONE     0x00000000
#define PMASK_PRESSURE 0x00000001
#define PMASK_ROTATION 0x00000002
#define PMASK_TILT_X   0x00000004
#define PMASK_TILT_Y   0x00000008

/* POINTER_FEEDBACK_MODE (ne-winuser-pointer_feedback_mode; starts at 1) */
#define FEEDBACK_DEFAULT   1
#define FEEDBACK_INDIRECT  2   /* overrides user settings - forces visible touch circles */
#define FEEDBACK_NONE      3

/* DPI_AWARENESS_CONTEXT pseudo-handles (winuser.h, Win10 1607+) */
#define DPI_CTX_UNAWARE        ((HANDLE)(INT_PTR)-1)
#define DPI_CTX_SYSTEM_AWARE   ((HANDLE)(INT_PTR)-2)
#define DPI_CTX_PER_MONITOR    ((HANDLE)(INT_PTR)-3)
#define DPI_CTX_PER_MONITOR_V2 ((HANDLE)(INT_PTR)-4)

#ifndef ERROR_NOT_READY
#define ERROR_NOT_READY 21L
#endif

/* ------------------------------------------------------------------ */
/* Struct replicas - field-for-field identical to WinUser.h 10.0.28000 */
/* (POINTER_INFO 96 B, POINTER_TOUCH_INFO 144 B, POINTER_PEN_INFO 120 B*/
/*  and POINTER_TYPE_INFO 152 B on x64; ButtonChangeType enum == 4 B)  */
/* ------------------------------------------------------------------ */

typedef struct MY_POINTER_INFO {
    DWORD  pointerType;          /* POINTER_INPUT_TYPE (typedef DWORD)   */
    UINT32 pointerId;
    UINT32 frameId;
    DWORD  pointerFlags;         /* POINTER_FLAGS (typedef DWORD)        */
    HANDLE sourceDevice;
    HWND   hwndTarget;
    POINT  ptPixelLocation;      /* relative to virtual screen top-left  */
    POINT  ptHimetricLocation;
    POINT  ptPixelLocationRaw;
    POINT  ptHimetricLocationRaw;
    DWORD  dwTime;               /* 0 = system-assigned                  */
    UINT32 historyCount;
    INT32  InputData;
    DWORD  dwKeyStates;
    UINT64 PerformanceCount;     /* 0 = system-assigned                  */
    UINT32 ButtonChangeType;     /* POINTER_BUTTON_CHANGE_TYPE (int)     */
} MY_POINTER_INFO;

typedef struct MY_POINTER_TOUCH_INFO {
    MY_POINTER_INFO pointerInfo;
    DWORD touchFlags;            /* TOUCH_FLAGS: only TOUCH_FLAG_NONE=0  */
    DWORD touchMask;             /* TOUCH_MASK bits                      */
    RECT  rcContact;
    RECT  rcContactRaw;
    UINT32 orientation;          /* 0-359 clockwise, 0 = along +X        */
    UINT32 pressure;             /* 0-1024, default 512                  */
} MY_POINTER_TOUCH_INFO;

typedef struct MY_POINTER_PEN_INFO {
    MY_POINTER_INFO pointerInfo;
    DWORD penFlags;              /* PEN_FLAGS: BARREL/INVERTED/ERASER    */
    DWORD penMask;               /* PEN_MASK bits                        */
    UINT32 pressure;             /* 0-1024, default 0                    */
    UINT32 rotation;             /* 0-359 clockwise twist                */
    INT32 tiltX;                 /* -90..+90, right is positive          */
    INT32 tiltY;                 /* -90..+90, toward user is positive    */
} MY_POINTER_PEN_INFO;

typedef struct MY_POINTER_TYPE_INFO {
    DWORD type;                  /* POINTER_INPUT_TYPE                   */
    union {
        MY_POINTER_INFO     pointerInfo;
        MY_POINTER_TOUCH_INFO touchInfo;
        MY_POINTER_PEN_INFO   penInfo;
    } u;                         /* DUMMYUNIONNAME - three branches      */
} MY_POINTER_TYPE_INFO;

/* Static-size sanity checks (x64): 96/144/120/152 bytes */
#ifdef _WIN64
C_ASSERT(sizeof(MY_POINTER_INFO) == 96);
C_ASSERT(sizeof(MY_POINTER_TOUCH_INFO) == 144);
C_ASSERT(sizeof(MY_POINTER_PEN_INFO) == 120);
C_ASSERT(sizeof(MY_POINTER_TYPE_INFO) == 152);
#endif

/* ------------------------------------------------------------------ */
/* Runtime-loaded function pointers                                    */
/* ------------------------------------------------------------------ */

typedef void*          (WINAPI *PFN_CreateSyntheticPointerDevice)(DWORD pointerType,
                                                                  ULONG maxCount,
                                                                  DWORD mode);
typedef BOOL           (WINAPI *PFN_InjectSyntheticPointerInput)(void* device,
                                                                  const MY_POINTER_TYPE_INFO* pointerInfo,
                                                                  UINT32 count);
typedef void           (WINAPI *PFN_DestroySyntheticPointerDevice)(void* device);
typedef BOOL           (WINAPI *PFN_SetProcessDpiAwarenessContext)(HANDLE value);
typedef HANDLE         (WINAPI *PFN_SetThreadDpiAwarenessContext)(HANDLE value);
typedef BOOL           (WINAPI *PFN_GetDpiForMonitor)(HMONITOR mon, int dpiType,
                                                      UINT* dpiX, UINT* dpiY);

static PFN_CreateSyntheticPointerDevice  pCreate;
static PFN_InjectSyntheticPointerInput   pInject;
static PFN_DestroySyntheticPointerDevice pDestroy;
static PFN_SetProcessDpiAwarenessContext pSetProcDpi;
static PFN_SetThreadDpiAwarenessContext  pSetThreadDpi;
static PFN_GetDpiForMonitor              pGetDpiForMonitor;

/* ------------------------------------------------------------------ */
/* Globals                                                             */
/* ------------------------------------------------------------------ */

static DWORD g_pointerType = PT_TOUCH;
static DWORD g_feedback    = FEEDBACK_INDIRECT;
static DWORD g_frameDelayMs = 8;     /* > 0.1 ms injection window, keeps
                                        ERROR_NOT_READY rare but the retry
                                        path below still handles it */
static UINT32 g_nextPointerId = 0;
static BOOL   g_useQpc        = FALSE;
static BOOL   g_rawCoord      = FALSE;  /* --raw: skip primary-origin conversion */
static LARGE_INTEGER g_qpcLast;

/* ------------------------------------------------------------------ */
/* Helpers                                                             */
/* ------------------------------------------------------------------ */

static void die(const char* msg, DWORD err)
{
    char buf[256];
    FormatMessageA(FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
                   NULL, err, MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT),
                   buf, sizeof(buf), NULL);
    fprintf(stderr, "[fatal] %s (err=%lu: %s)\n", msg, err, buf);
    exit(1);
}

static void* load_user32(const char* name)
{
    void* p = (void*)GetProcAddress(GetModuleHandleA("user32.dll"), name);
    if (!p) {
        fprintf(stderr, "[fatal] user32.dll!%s not found.\n", name);
        fprintf(stderr, "        InjectSyntheticPointerInput requires Windows 10 1809+ (build 17763).\n");
        exit(2);
    }
    return p;
}

static void load_apis(void)
{
    pCreate        = (PFN_CreateSyntheticPointerDevice)load_user32("CreateSyntheticPointerDevice");
    pInject        = (PFN_InjectSyntheticPointerInput)load_user32("InjectSyntheticPointerInput");
    pDestroy       = (PFN_DestroySyntheticPointerDevice)load_user32("DestroySyntheticPointerDevice");
    pSetProcDpi    = (PFN_SetProcessDpiAwarenessContext)GetProcAddress(
                         GetModuleHandleA("user32.dll"), "SetProcessDpiAwarenessContext");
    pSetThreadDpi  = (PFN_SetThreadDpiAwarenessContext)GetProcAddress(
                         GetModuleHandleA("user32.dll"), "SetThreadDpiAwarenessContext");
    pGetDpiForMonitor = (PFN_GetDpiForMonitor)(void*)GetProcAddress(
                         GetModuleHandleA("shcore.dll"), "GetDpiForMonitor");
    /* NOTE: g_qpcLast is intentionally left at 0 (static init) - it stores
     * the previous frame's QPC value, not the frequency.                   */
}

/* Set thread DPI awareness context; returns previous context (or NULL on
 * pre-1607 systems, where the whole DPI question is moot anyway).      */
static HANDLE push_dpi_ctx(HANDLE ctx)
{
    if (pSetThreadDpi) return pSetThreadDpi(ctx);
    return NULL;
}

static void pop_dpi_ctx(HANDLE prev)
{
    if (pSetThreadDpi && prev) pSetThreadDpi(prev);
}

/* Inject one frame; retries the *same* frame on ERROR_NOT_READY.
 * Official rule: 0.1 ms settlement window -> same-frame retry is required. */
static BOOL inject_frame(void* device, MY_POINTER_TYPE_INFO* frame, UINT32 count)
{
    for (int attempt = 0; ; attempt++) {
        if (pInject(device, frame, count)) return TRUE;
        DWORD err = GetLastError();
        if (err == ERROR_NOT_READY && attempt < 200) {
            fprintf(stderr, "[inject] ERROR_NOT_READY (0.1ms window) - retrying same frame #%d\n", attempt + 1);
            Sleep(1);
            continue;
        }
        fprintf(stderr, "[inject] failed: GetLastError=%lu\n", err);
        return FALSE;
    }
}

/* Fill the common POINTER_INFO header of a frame */
static void fill_header(MY_POINTER_INFO* pi, LONG x, LONG y, DWORD flags)
{
    ZeroMemory(pi, sizeof(*pi));
    pi->pointerType     = g_pointerType;
    pi->pointerId       = g_nextPointerId;
    pi->frameId         = 0;          /* system-managed for injection   */
    pi->pointerFlags    = flags;
    pi->sourceDevice    = NULL;
    pi->hwndTarget      = NULL;       /* system routes to the session   */
    pi->ptPixelLocation.x     = x;
    pi->ptPixelLocation.y     = y;
    pi->ptPixelLocationRaw    = pi->ptPixelLocation;
    pi->ptHimetricLocationRaw = pi->ptPixelLocation;
    pi->ptHimetricLocation    = pi->ptPixelLocation;
    pi->historyCount    = 1;
    pi->dwKeyStates     = 0;
    pi->dwTime          = 0;          /* system-assigned timestamp      */
    pi->PerformanceCount = 0;
    if (g_useQpc) {
        LARGE_INTEGER now;
        QueryPerformanceCounter(&now);
        /* must strictly increase every frame once provided (official rule) */
        if (now.QuadPart <= g_qpcLast.QuadPart) now.QuadPart = g_qpcLast.QuadPart + 1;
        g_qpcLast = now;
        pi->PerformanceCount = (UINT64)now.QuadPart;
    }
}

/* One complete contact sequence per the official state machine:
 *   hover start : INRANGE|UPDATE
 *   contact down: INRANGE|INCONTACT|DOWN|FIRSTBUTTON
 *   move        : INRANGE|INCONTACT|UPDATE|FIRSTBUTTON   (x N)
 *   contact up  : INRANGE|UP
 *   hover end   : UPDATE
 */
static BOOL inject_contact_sequence(void* device, LONG x1, LONG y1, LONG x2, LONG y2,
                                    int moveFrames)
{
    BOOL ok = TRUE;
    MY_POINTER_TYPE_INFO frame;

    /* hover start */
    ZeroMemory(&frame, sizeof(frame));
    if (g_pointerType == PT_TOUCH) {
        frame.type = PT_TOUCH;
        fill_header(&frame.u.touchInfo.pointerInfo, x1, y1, PFLAG_INRANGE | PFLAG_UPDATE);
        frame.u.touchInfo.touchFlags = 0;
        frame.u.touchInfo.touchMask  = TMASK_CONTACTAREA;
        frame.u.touchInfo.rcContact.left   = x1 - 4; frame.u.touchInfo.rcContact.top    = y1 - 4;
        frame.u.touchInfo.rcContact.right  = x1 + 4; frame.u.touchInfo.rcContact.bottom = y1 + 4;
        frame.u.touchInfo.rcContactRaw = frame.u.touchInfo.rcContact;
        frame.u.touchInfo.pressure = 512;   /* official default */
    } else {
        frame.type = PT_PEN;
        fill_header(&frame.u.penInfo.pointerInfo, x1, y1, PFLAG_INRANGE | PFLAG_UPDATE);
        frame.u.penInfo.penFlags = 0;
        frame.u.penInfo.penMask  = PMASK_NONE;  /* optional fields ignored */
        frame.u.penInfo.pressure = 0;           /* official default when device reports none */
        frame.u.penInfo.rotation = 0;
        frame.u.penInfo.tiltX = 0; frame.u.penInfo.tiltY = 0;
    }
    ok &= inject_frame(device, &frame, 1);
    Sleep(g_frameDelayMs);

    /* contact down (official: touch has FIRSTBUTTON while in contact) */
    ZeroMemory(&frame, sizeof(frame));
    if (g_pointerType == PT_TOUCH) {
        frame.type = PT_TOUCH;
        fill_header(&frame.u.touchInfo.pointerInfo, x1, y1,
                    PFLAG_INRANGE | PFLAG_INCONTACT | PFLAG_DOWN | PFLAG_FIRSTBUTTON);
        frame.u.touchInfo.touchMask = TMASK_CONTACTAREA;
        frame.u.touchInfo.rcContact.left   = x1 - 4; frame.u.touchInfo.rcContact.top    = y1 - 4;
        frame.u.touchInfo.rcContact.right  = x1 + 4; frame.u.touchInfo.rcContact.bottom = y1 + 4;
        frame.u.touchInfo.rcContactRaw = frame.u.touchInfo.rcContact;
        frame.u.touchInfo.pressure = 512;
    } else {
        frame.type = PT_PEN;
        fill_header(&frame.u.penInfo.pointerInfo, x1, y1,
                    PFLAG_INRANGE | PFLAG_INCONTACT | PFLAG_DOWN | PFLAG_FIRSTBUTTON);
        frame.u.penInfo.penMask = PMASK_NONE;
        frame.u.penInfo.pressure = 700;
    }
    ok &= inject_frame(device, &frame, 1);
    Sleep(g_frameDelayMs);

    /* move */
    for (int i = 1; i <= moveFrames; i++) {
        LONG x = x1 + (LONG)((double)(x2 - x1) * i / moveFrames);
        LONG y = y1 + (LONG)((double)(y2 - y1) * i / moveFrames);
        ZeroMemory(&frame, sizeof(frame));
        if (g_pointerType == PT_TOUCH) {
            frame.type = PT_TOUCH;
            fill_header(&frame.u.touchInfo.pointerInfo, x, y,
                        PFLAG_INRANGE | PFLAG_INCONTACT | PFLAG_UPDATE | PFLAG_FIRSTBUTTON);
            frame.u.touchInfo.touchMask = TMASK_CONTACTAREA;
            frame.u.touchInfo.rcContact.left   = x - 4; frame.u.touchInfo.rcContact.top    = y - 4;
            frame.u.touchInfo.rcContact.right  = x + 4; frame.u.touchInfo.rcContact.bottom = y + 4;
            frame.u.touchInfo.rcContactRaw = frame.u.touchInfo.rcContact;
            frame.u.touchInfo.pressure = 512;
        } else {
            frame.type = PT_PEN;
            fill_header(&frame.u.penInfo.pointerInfo, x, y,
                        PFLAG_INRANGE | PFLAG_INCONTACT | PFLAG_UPDATE | PFLAG_FIRSTBUTTON);
            frame.u.penInfo.penMask = PMASK_NONE;
            frame.u.penInfo.pressure = 700;
        }
        ok &= inject_frame(device, &frame, 1);
        Sleep(g_frameDelayMs);
    }

    /* contact up -> hover (official: INRANGE | UP) */
    ZeroMemory(&frame, sizeof(frame));
    if (g_pointerType == PT_TOUCH) {
        frame.type = PT_TOUCH;
        fill_header(&frame.u.touchInfo.pointerInfo, x2, y2, PFLAG_INRANGE | PFLAG_UP);
        frame.u.touchInfo.touchMask = TMASK_CONTACTAREA;
        frame.u.touchInfo.rcContact.left   = x2 - 4; frame.u.touchInfo.rcContact.top    = y2 - 4;
        frame.u.touchInfo.rcContact.right  = x2 + 4; frame.u.touchInfo.rcContact.bottom = y2 + 4;
        frame.u.touchInfo.rcContactRaw = frame.u.touchInfo.rcContact;
        frame.u.touchInfo.pressure = 512;
    } else {
        frame.type = PT_PEN;
        fill_header(&frame.u.penInfo.pointerInfo, x2, y2, PFLAG_INRANGE | PFLAG_UP);
        frame.u.penInfo.penMask = PMASK_NONE;
    }
    ok &= inject_frame(device, &frame, 1);
    Sleep(g_frameDelayMs);

    /* hover end -> sequence end (official: plain UPDATE, no INRANGE) */
    ZeroMemory(&frame, sizeof(frame));
    if (g_pointerType == PT_TOUCH) {
        frame.type = PT_TOUCH;
        fill_header(&frame.u.touchInfo.pointerInfo, x2, y2, PFLAG_UPDATE);
        frame.u.touchInfo.touchMask = TMASK_NONE;
    } else {
        frame.type = PT_PEN;
        fill_header(&frame.u.penInfo.pointerInfo, x2, y2, PFLAG_UPDATE);
        frame.u.penInfo.penMask = PMASK_NONE;
    }
    ok &= inject_frame(device, &frame, 1);
    Sleep(g_frameDelayMs);

    return ok;
}

static BOOL inject_single_click(void* device, LONG x, LONG y)
{
    g_nextPointerId++;
    return inject_contact_sequence(device, x, y, x, y, 2);
}

/* ------------------------------------------------------------------ */
/* Monitor enumeration (called inside a PMv2 thread context so that    */
/* GetDpiForMonitor / GetSystemMetrics report physical values)         */
/* ------------------------------------------------------------------ */

static BOOL CALLBACK monitor_cb(HMONITOR mon, HDC dc, LPRECT rc, LPARAM data)
{
    (void)dc; (void)data;
    MONITORINFOEXA mi;
    ZeroMemory(&mi, sizeof(mi));
    mi.cbSize = sizeof(mi);
    char name[64] = "?";
    if (GetMonitorInfoA(mon, &mi)) {
        lstrcpynA(name, mi.szDevice, sizeof(name));
    }
    printf("    monitor %-9s rcMonitor={%ld,%ld,%ld,%ld} size=%ldx%ld",
           name, (long)rc->left, (long)rc->top, (long)rc->right, (long)rc->bottom,
           (long)(rc->right - rc->left), (long)(rc->bottom - rc->top));
    if (pGetDpiForMonitor) {
        UINT dx = 0, dy = 0;
        if (pGetDpiForMonitor(mon, 0 /* MDT_EFFECTIVE_DPI */, &dx, &dy) == S_OK)
            printf(" dpi=%u (%.0f%%)", dx, dx * 100.0 / 96.0);
    }
    if (mi.dwFlags & MONITORINFOF_PRIMARY) printf(" [PRIMARY]");
    printf("\n");
    return TRUE;
}

static void print_layout(void)
{
    printf("  virtual screen : left=%ld top=%ld size=%ldx%ld  (SM_X/Y/CX/CYVIRTUALSCREEN)\n",
           (long)GetSystemMetrics(SM_XVIRTUALSCREEN),
           (long)GetSystemMetrics(SM_YVIRTUALSCREEN),
           (long)GetSystemMetrics(SM_CXVIRTUALSCREEN),
           (long)GetSystemMetrics(SM_CYVIRTUALSCREEN));
    printf("  primary screen : %ldx%ld (SM_CX/SM_CYSCREEN)\n",
           (long)GetSystemMetrics(SM_CXSCREEN), (long)GetSystemMetrics(SM_CYSCREEN));
    printf("  monitors (thread context = PMv2, physical values):\n");
    EnumDisplayMonitors(NULL, NULL, monitor_cb, 0);
}

/* ------------------------------------------------------------------ */
/* Experiments                                                         */
/* ------------------------------------------------------------------ */

static int run_origin_experiment(void)
{
    HANDLE prev = push_dpi_ctx(DPI_CTX_PER_MONITOR_V2);

    printf("=== Experiment A: virtual-screen origin ===\n");
    print_layout();

    void* dev = pCreate(g_pointerType, 1, g_feedback);
    if (!dev) die("CreateSyntheticPointerDevice", GetLastError());

    printf("\n  injecting ONE click at coordinate (5,5) ...\n");
    printf("  VERDICT (measured on real HW 2026-10-10, secondary on the left):\n");
    printf("    circle appeared on the LEFT secondary upper-left ->\n");
    printf("    origin = virtual-screen bounding-box upper-left (SM_X/YVIRTUALSCREEN),\n");
    printf("    NOT the primary-monitor (0,0). Injected coords are physical-pixel\n");
    printf("    offsets from the bounding-box corner, DPI-awareness independent.\n");
    printf("    Conversion: inject_xy = target_xy(primary-origin) - (SM_XVIRT, SM_YVIRT)\n");
    printf("    (single-monitor: both semantics coincide, SM offsets = 0)\n");
    printf("  expected circle (primary-origin coords): (%ld, %ld)\n",
           (long)(5 + GetSystemMetrics(SM_XVIRTUALSCREEN)),
           (long)(5 + GetSystemMetrics(SM_YVIRTUALSCREEN)));

    Sleep(800);
    BOOL ok = inject_single_click(dev, 5, 5);
    printf("  injection %s\n", ok ? "OK" : "FAILED");

    pDestroy(dev);
    pop_dpi_ctx(prev);
    return ok ? 0 : 1;
}

static int run_dpi_experiment(int scalePctGuess)
{
    printf("=== Experiment B: DPI coordinate space of the injection thread ===\n");

    HANDLE prevPm = push_dpi_ctx(DPI_CTX_PER_MONITOR_V2);
    printf("[context PMv2    ] SM_CXSCREEN = %ld\n", (long)GetSystemMetrics(SM_CXSCREEN));
    print_layout();
    pop_dpi_ctx(prevPm);

    HANDLE prevUn = push_dpi_ctx(DPI_CTX_UNAWARE);
    printf("[context unaware ] SM_CXSCREEN = %ld   <- if scaled down by 1/%.2f, the\n",
           (long)GetSystemMetrics(SM_CXSCREEN), scalePctGuess / 100.0);
    printf("                                     'calling-thread coordinate space' rule applies\n");
    pop_dpi_ctx(prevUn);

    void* dev = pCreate(g_pointerType, 1, g_feedback);
    if (!dev) die("CreateSyntheticPointerDevice", GetLastError());

    int scaledX = 200 * scalePctGuess / 100;
    printf("\n  injecting click #1 with thread context = PMv2    at coordinate (200,200)\n");
    printf("  injecting click #2 with thread context = unaware at coordinate (200,200)\n");
    printf("  on a %d%% display the two circles tell the verdict:\n", scalePctGuess);
    printf("    both at physical (200,200)              -> API passes physical pixels as-is\n");
    printf("    PMv2 at (200,200), unaware at (%d,%d)   -> coordinate space follows the thread\n",
           scaledX, scaledX);
    printf("    both at (%d,%d)                         -> API always logical (unlikely)\n",
           scaledX, scaledX);

    Sleep(800);
    HANDLE prev = push_dpi_ctx(DPI_CTX_PER_MONITOR_V2);
    BOOL ok1 = inject_single_click(dev, 200, 200);
    pop_dpi_ctx(prev);
    printf("  click #1 (PMv2)     %s - where was the circle?\n", ok1 ? "OK" : "FAILED");
    Sleep(1200);

    prev = push_dpi_ctx(DPI_CTX_UNAWARE);
    BOOL ok2 = inject_single_click(dev, 200, 200);
    pop_dpi_ctx(prev);
    printf("  click #2 (unaware)  %s - where was the circle?\n", ok2 ? "OK" : "FAILED");
    Sleep(1200);

    pDestroy(dev);
    return (ok1 && ok2) ? 0 : 1;
}

/* ------------------------------------------------------------------ */
/* Tool modes                                                          */
/* ------------------------------------------------------------------ */

static int run_tool(int mode, LONG* pts, int moveFrames)
{
    void* dev = pCreate(g_pointerType, 1, g_feedback);
    if (!dev) die("CreateSyntheticPointerDevice", GetLastError());

    /* Measured verdict (2026-10-10): injected coords are physical-pixel
     * offsets from the virtual-screen BOUNDING-BOX upper-left, i.e.
     * primary-origin coords minus (SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN).
     * Tool input X/Y below is in the familiar primary-origin screen space
     * (same space as EnumDisplayMonitors / GetCursorPos), so we convert;
     * pass --raw to feed the API raw bounding-box-relative values.      */
    if (!g_rawCoord) {
        LONG smx = (LONG)GetSystemMetrics(SM_XVIRTUALSCREEN);
        LONG smy = (LONG)GetSystemMetrics(SM_YVIRTUALSCREEN);
        printf("[convert] primary-origin (%ld,%ld) -> bounding-box-relative (%ld,%ld)\n",
               (long)pts[0], (long)pts[1], (long)(pts[0] - smx), (long)(pts[1] - smy));
        pts[0] -= smx; pts[1] -= smy;
        if (mode == 1) { pts[2] -= smx; pts[3] -= smy; }
    }

    BOOL ok;
    if (mode == 0) {           /* click */
        ok = inject_single_click(dev, pts[0], pts[1]);
    } else {                   /* stroke */
        g_nextPointerId++;
        ok = inject_contact_sequence(dev, pts[0], pts[1], pts[2], pts[3], moveFrames);
    }
    pDestroy(dev);
    return ok ? 0 : 1;
}

/* ------------------------------------------------------------------ */
/* Main                                                                */
/* ------------------------------------------------------------------ */

static void usage(void)
{
    printf(
"synthetic_pointer_probe - InjectSyntheticPointerInput experiment tool\n"
"\n"
"  synthetic_pointer_probe.exe --test origin\n"
"  synthetic_pointer_probe.exe --test dpi [--scale 150]\n"
"  synthetic_pointer_probe.exe --click X Y\n"
"  synthetic_pointer_probe.exe --stroke X1 Y1 X2 Y2 [--frames 20]\n"
"\n"
"Options:\n"
"  --type touch|pen       pointer type           (default touch)\n"
"  --dpi pmv2|pm|system|unaware|none   process-level awareness at startup\n"
"                                 (default pmv2; 'none' leaves process default)\n"
"  --feedback indirect|default|none    touch visualization (default indirect,\n"
"                                 which FORCES visible circles - good for judging)\n"
"  --raw                  treat X/Y as raw bounding-box-relative values (default:\n"
"                                 X/Y are primary-origin screen coords, converted\n"
"                                 internally by subtracting SM_X/YVIRTUALSCREEN,\n"
"                                 per the measured coordinate verdict)\n"
"  --qpc                  provide PerformanceCount timestamps (advanced;\n"
"                         once started they must continue until all UP)\n"
"  --delay MS             frame delay (default 8 ms)\n"
"\n"
"Experiments resolve (see README.md for the verdict tables):\n"
"  origin : MEASURED - bounding-box upper-left (SM_X/YVIRTUALSCREEN),\n"
"           NOT the primary monitor (0,0)\n"
"  dpi    : MEASURED - physical pixels as-is, thread awareness ignored.\n");
}

int main(int argc, char** argv)
{
    int   mode       = -1;        /* 0=click 1=stroke 2=origin 3=dpi */
    LONG  pts[4]     = {0};
    int   npts       = 0;
    int   moveFrames = 20;
    int   scaleGuess = 150;
    const char* dpiArg  = "pmv2";
    const char* typeArg = "touch";

    for (int i = 1; i < argc; i++) {
        #define NEXT() (++i < argc ? argv[i] : NULL)
        if (!strcmp(argv[i], "--test") && NEXT()) {
            if (!strcmp(argv[i], "origin")) mode = 2;
            else if (!strcmp(argv[i], "dpi")) mode = 3;
            else { usage(); return 1; }
        } else if (!strcmp(argv[i], "--click")) {
            mode = 0; npts = 0;
            for (int k = 0; k < 2 && i + 1 < argc; k++) pts[npts++] = atol(argv[++i]);
        } else if (!strcmp(argv[i], "--stroke")) {
            mode = 1; npts = 0;
            for (int k = 0; k < 4 && i + 1 < argc; k++) pts[npts++] = atol(argv[++i]);
        } else if (!strcmp(argv[i], "--frames") && NEXT()) {
            moveFrames = atoi(argv[i]);
        } else if (!strcmp(argv[i], "--scale") && NEXT()) {
            scaleGuess = atoi(argv[i]);
        } else if (!strcmp(argv[i], "--type") && NEXT()) {
            typeArg = argv[i];
        } else if (!strcmp(argv[i], "--dpi") && NEXT()) {
            dpiArg = argv[i];
        } else if (!strcmp(argv[i], "--feedback") && NEXT()) {
            const char* f = argv[i];
            g_feedback = !strcmp(f, "default") ? FEEDBACK_DEFAULT :
                         !strcmp(f, "none")    ? FEEDBACK_NONE : FEEDBACK_INDIRECT;
        } else if (!strcmp(argv[i], "--qpc")) {
            g_useQpc = TRUE;
        } else if (!strcmp(argv[i], "--raw")) {
            g_rawCoord = TRUE;   /* feed API raw bounding-box-relative coords */
        } else if (!strcmp(argv[i], "--delay") && NEXT()) {
            g_frameDelayMs = atoi(argv[i]);
        } else if (!strcmp(argv[i], "-h") || !strcmp(argv[i], "--help")) {
            usage(); return 0;
        } else {
            fprintf(stderr, "unknown arg: %s\n\n", argv[i]);
            usage(); return 1;
        }
        #undef NEXT
    }

    if (mode < 0) { usage(); return 1; }

    g_pointerType = !strcmp(typeArg, "pen") ? PT_PEN : PT_TOUCH;

    load_apis();

    /* Process-level DPI awareness must be set before any window is created,
     * and only once per process. 'none' keeps the default (unaware).      */
    if (pSetProcDpi && strcmp(dpiArg, "none") != 0) {
        HANDLE ctx = !strcmp(dpiArg, "pmv2")   ? DPI_CTX_PER_MONITOR_V2 :
                     !strcmp(dpiArg, "pm")     ? DPI_CTX_PER_MONITOR :
                     !strcmp(dpiArg, "system") ? DPI_CTX_SYSTEM_AWARE :
                                                 DPI_CTX_UNAWARE;
        if (!pSetProcDpi(ctx))
            fprintf(stderr, "[warn] SetProcessDpiAwarenessContext failed (err=%lu); continuing\n",
                    GetLastError());
    }

    printf("pointer type = %s | feedback = %s | frame delay = %d ms\n",
           g_pointerType == PT_PEN ? "pen (PT_PEN)" : "touch (PT_TOUCH)",
           g_feedback == FEEDBACK_INDIRECT ? "indirect(forced)" :
           g_feedback == FEEDBACK_NONE ? "none" : "default", g_frameDelayMs);

    switch (mode) {
    case 2: return run_origin_experiment();
    case 3: return run_dpi_experiment(scaleGuess);
    case 0:
        if (npts < 2) { fprintf(stderr, "--click needs X Y\n"); return 1; }
        return run_tool(0, pts, moveFrames);
    case 1:
        if (npts < 4) { fprintf(stderr, "--stroke needs X1 Y1 X2 Y2\n"); return 1; }
        return run_tool(1, pts, moveFrames);
    }
    return 0;
}
