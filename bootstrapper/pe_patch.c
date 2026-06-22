// pe_patch.c - PE post-processor for RocoMapTracker single-file distribution
//
// Auto-discovers all VC++ runtime imports, strips them from the PE import
// directory, embeds ALL DLLs (VC++ + JVM + JavaFX) into a new .rmtldr section,
// and changes the entry point to a loader stub.
//
// The stub at runtime:
//   1. Creates dll/ directory next to the exe
//   2. Adds dll/ to the process DLL search path
//   3. Extracts all embedded DLLs to dll/
//   4. LoadLibrary + patch IAT for VC++ runtime DLLs
//   5. Preloads JVM/JavaFX DLLs (so System.loadLibrary() later is a no-op)
//   6. Jumps to the original entry point
//
// Compile (VS x64 prompt):
//   cl /nologo /O1 /GS- /Fe pe_patch.exe pe_patch.c
//
// Run:
//   pe_patch --engine engine.exe --output RocoMapTracker.exe --stub loader_stub.obj ^
//            --embed vcruntime140.dll=path\to\vcruntime140.dll ^
//            --embed msvcp140.dll=path\to\msvcp140.dll ^
//            --embed awt.dll=path\to\awt.dll --preload awt ^
//            ...

#define _CRT_SECURE_NO_WARNINGS
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <errno.h>

// ============================================================
// PE structures
// ============================================================

#pragma pack(push, 1)
typedef struct {
    uint16_t e_magic;
    uint16_t e_cblp, e_cp, e_crlc, e_cparhdr, e_minalloc, e_maxalloc;
    uint16_t e_ss, e_sp, e_csum, e_ip, e_cs, e_lfarlc, e_ovno;
    uint16_t e_res[4], e_oemid, e_oeminfo, e_res2[10];
    uint32_t e_lfanew;
} IMAGE_DOS_HEADER;

typedef struct {
    uint32_t Signature;
    uint16_t Machine, NumberOfSections;
    uint32_t TimeDateStamp, PointerToSymbolTable, NumberOfSymbols;
    uint16_t SizeOfOptionalHeader, Characteristics;
} IMAGE_FILE_HEADER;

typedef struct {
    uint16_t Magic;
    uint8_t  MajorLinkerVersion, MinorLinkerVersion;
    uint32_t SizeOfCode, SizeOfInitializedData, SizeOfUninitializedData;
    uint32_t AddressOfEntryPoint, BaseOfCode;
    uint64_t ImageBase;
    uint32_t SectionAlignment, FileAlignment;
    uint16_t MajorOperatingSystemVersion, MinorOperatingSystemVersion;
    uint16_t MajorImageVersion, MinorImageVersion;
    uint16_t MajorSubsystemVersion, MinorSubsystemVersion;
    uint32_t Win32VersionValue, SizeOfImage, SizeOfHeaders, CheckSum;
    uint16_t Subsystem, DllCharacteristics;
    uint64_t SizeOfStackReserve, SizeOfStackCommit;
    uint64_t SizeOfHeapReserve, SizeOfHeapCommit;
    uint32_t LoaderFlags, NumberOfRvaAndSizes;
} IMAGE_OPTIONAL_HEADER64;

typedef struct {
    uint32_t VirtualAddress, Size;
} IMAGE_DATA_DIRECTORY;

typedef struct {
    uint8_t  Name[8];
    uint32_t VirtualSize, VirtualAddress, SizeOfRawData, PointerToRawData;
    uint32_t PointerToRelocations, PointerToLinenumbers;
    uint16_t NumberOfRelocations, NumberOfLinenumbers;
    uint32_t Characteristics;
} IMAGE_SECTION_HEADER;

typedef struct {
    uint32_t OriginalFirstThunk, TimeDateStamp, ForwarderChain, Name, FirstThunk;
} IMAGE_IMPORT_DESCRIPTOR;

// COFF file header (for loader_stub.obj)
typedef struct {
    uint16_t Machine, NumberOfSections;
    uint32_t TimeDateStamp, PointerToSymbolTable, NumberOfSymbols;
    uint16_t SizeOfOptionalHeader, Characteristics;
} COFF_FILE_HEADER;

typedef struct {
    char     Name[8];
    uint32_t VirtualSize, VirtualAddress, SizeOfRawData, PointerToRawData;
    uint32_t PointerToRelocations, PointerToLinenumbers;
    uint16_t NumberOfRelocations, NumberOfLinenumbers;
    uint32_t Characteristics;
} COFF_SECTION_HEADER;
#pragma pack(pop)

// ============================================================
// .rmtldr section structures (must match loader_stub.asm)
// ============================================================

#define RMT_MAGIC   0x4C544D52  // "RMTL"
#define RMT_VERSION 1
#define CONTEXT_OFFSET 0x1000

// Per-DLL extraction entry
typedef struct {
    uint32_t name_offset;     // offset into name table (ASCII filename)
    uint32_t data_offset;     // offset from .rmtldr base to DLL binary
    uint32_t data_size;       // size of DLL binary
    uint32_t flags;           // bit 0 = preload, bit 1 = patch IAT
} DLL_EMBED_ENTRY;

#define EMBED_FLAG_PRELOAD   1
#define EMBED_FLAG_PATCH_IAT 2

// Per-DLL fixup table header
typedef struct {
    uint32_t dll_name_offset; // offset into name table
    uint32_t fixup_offset;    // offset to first FIXUP_ENTRY
    uint32_t fixup_count;     // number of entries
} DLL_FIXUP_TABLE;

// Single IAT slot fixup
typedef struct {
    uint32_t iat_rva;
    uint32_t name_offset;     // function name in name table
} FIXUP_ENTRY;

// Preload-only entry (bare name like "awt")
typedef struct {
    uint32_t name_offset;     // offset into name table
} DLL_PRELOAD_ENTRY;

// Root context at CONTEXT_OFFSET
typedef struct {
    uint32_t magic;            // RMT_MAGIC
    uint32_t version;          // 1
    uint32_t extract_offset;   // DLL_EMBED_ENTRY array
    uint32_t extract_count;
    uint32_t fixup_offset;     // DLL_FIXUP_TABLE array
    uint32_t fixup_count;      // one per stripped VC++ DLL
    uint32_t preload_offset;   // DLL_PRELOAD_ENTRY array
    uint32_t preload_count;
    uint32_t name_table;       // name string table
    uint32_t orig_entry;       // original entry point RVA
    uint32_t stub_funcs;       // function pointer slots (11 * 8 = 88 bytes)
} RMT_LOADER_CONTEXT;

// ============================================================
// VC++ runtime DLL detection
// ============================================================

// Known VC++ runtime DLL name patterns to auto-detect in import table
static const char *VC_RUNTIME_DLLS[] = {
    "VCRUNTIME140.dll",
    "VCRUNTIME140_1.dll",
    "MSVCP140.dll",
    "MSVCP140_1.dll",
    "MSVCP140_2.dll",
    "CONCRT140.dll",
    "VCRUNTIME140_THREADS.dll",
    "MSVCP140_ATOMIC_WAIT.dll",
    NULL
};

// Transitive DLL dependencies within VC++ runtime
// If DLL A is in the import table, DLL B must also be embedded.
static const char *VC_DEPENDENCIES[][2] = {
    {"MSVCP140_1.dll", "MSVCP140.dll"},
    {"MSVCP140_2.dll", "MSVCP140.dll"},
    {"MSVCP140_ATOMIC_WAIT.dll", "MSVCP140.dll"},
    {"VCRUNTIME140_1.dll", "VCRUNTIME140.dll"},
    {"CONCRT140.dll", "MSVCP140.dll"},
    {NULL, NULL}
};

// Built-in kernel32 function names the stub needs
#define NUM_KERNEL32_NAMES 11
static const char *kernel32_names[] = {
    "LoadLibraryW",
    "GetProcAddress",
    "VirtualProtect",
    "GetModuleFileNameW",
    "CreateDirectoryW",
    "CreateFileW",
    "WriteFile",
    "CloseHandle",
    "FlushFileBuffers",
    "SetDefaultDllDirectories",
    "AddDllDirectory",
};

// ============================================================
// Helpers
// ============================================================

static uint64_t read_u64le(const uint8_t *p) {
    return (uint64_t)p[0] | ((uint64_t)p[1] << 8) |
           ((uint64_t)p[2] << 16) | ((uint64_t)p[3] << 24) |
           ((uint64_t)p[4] << 32) | ((uint64_t)p[5] << 40) |
           ((uint64_t)p[6] << 48) | ((uint64_t)p[7] << 56);
}

static uint32_t read_u32le(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static uint16_t read_u16le(const uint8_t *p) {
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

static void die(const char *msg) {
    fprintf(stderr, "FATAL: %s\n", msg);
    exit(1);
}

static void dief(const char *fmt, const char *arg) {
    fprintf(stderr, "FATAL: ");
    fprintf(stderr, fmt, arg);
    fprintf(stderr, "\n");
    exit(1);
}

static void *read_whole_file(const char *path, size_t *out_size) {
    FILE *f = fopen(path, "rb");
    if (!f) dief("cannot open '%s'", path);
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    if (size < 0) { fclose(f); dief("ftell failed on '%s'", path); }
    fseek(f, 0, SEEK_SET);
    uint8_t *buf = (uint8_t *)malloc((size_t)size + 16);
    if (!buf) { fclose(f); die("out of memory"); }
    size_t got = fread(buf, 1, (size_t)size, f);
    fclose(f);
    if ((long)got != size) dief("short read on '%s'", path);
    if (out_size) *out_size = (size_t)size;
    return buf;
}

static long rva_to_offset(const uint8_t *pe_start,
                           const IMAGE_SECTION_HEADER *sec_hdrs,
                           int num_sec, uint32_t rva) {
    for (int i = 0; i < num_sec; i++) {
        uint32_t rva_start = sec_hdrs[i].VirtualAddress;
        uint32_t rva_end   = rva_start + sec_hdrs[i].VirtualSize;
        if (rva >= rva_start && rva < rva_end)
            return (long)(rva - rva_start + sec_hdrs[i].PointerToRawData);
    }
    return -1;
}

static int is_vc_runtime(const char *dll_name) {
    for (int i = 0; VC_RUNTIME_DLLS[i]; i++)
        if (_stricmp(dll_name, VC_RUNTIME_DLLS[i]) == 0)
            return 1;
    return 0;
}

// ============================================================
// Embedded DLL bookkeeping
// ============================================================

#define MAX_EMBED 64
#define MAX_EMBED_DATA (256 * 1024 * 1024) // 256 MB max total DLL data

typedef struct {
    char    name[128];       // DLL filename (e.g. "vcruntime140.dll")
    char    file_path[512];  // source file path
    uint8_t *data;
    size_t  data_size;
    int     flags;           // EMBED_FLAG_PRELOAD | EMBED_FLAG_PATCH_IAT
    int     is_vc;           // 1 if this is a VC++ runtime DLL
} EMBED_INFO;

static EMBED_INFO embed_list[MAX_EMBED];
static int embed_count = 0;

// Preload-only names (no file to embed, just a LoadLibraryW hint)
static char preload_names[MAX_EMBED][128];
static int preload_count = 0;

// ============================================================
// Import fixup bookkeeping
// ============================================================

#define MAX_FIXUPS 256
#define MAX_FIXUP_DLLS 16

typedef struct {
    char     name[128];   // function name
    uint32_t iat_rva;
} FUNC_FIXUP;

typedef struct {
    char      dll_name[128];
    FUNC_FIXUP fixups[MAX_FIXUPS];
    int       fixup_count;
} DLL_FIXUP_INFO;

static DLL_FIXUP_INFO fixup_dlls[MAX_FIXUP_DLLS];
static int fixup_dll_count = 0;

// ============================================================
// Main
// ============================================================

int main(int argc, char **argv) {
    const char *engine_path  = NULL;
    const char *output_path  = NULL;
    const char *stub_obj_path = NULL;

    // Parse args
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--engine") == 0 && i + 1 < argc)
            engine_path = argv[++i];
        else if (strcmp(argv[i], "--output") == 0 && i + 1 < argc)
            output_path = argv[++i];
        else if (strcmp(argv[i], "--stub") == 0 && i + 1 < argc)
            stub_obj_path = argv[++i];
        else if (strcmp(argv[i], "--embed") == 0 && i + 1 < argc) {
            // Format: --embed name.dll=path\to\file.dll
            char *arg = argv[++i];
            char *eq = strchr(arg, '=');
            if (!eq || embed_count >= MAX_EMBED) {
                fprintf(stderr, "WARNING: bad --embed format '%s' (need name=path)\n", arg);
                continue;
            }
            *eq = '\0';
            EMBED_INFO *e = &embed_list[embed_count++];
            memset(e, 0, sizeof(*e));
            strncpy(e->name, arg, sizeof(e->name) - 1);
            strncpy(e->file_path, eq + 1, sizeof(e->file_path) - 1);
        }
        else if (strcmp(argv[i], "--preload") == 0 && i + 1 < argc) {
            if (preload_count >= MAX_EMBED) {
                fprintf(stderr, "WARNING: too many --preload args\n");
                continue;
            }
            strncpy(preload_names[preload_count++], argv[++i], 127);
        }
    }

    if (!engine_path || !output_path || !stub_obj_path) {
        fprintf(stderr,
            "Usage: pe_patch --engine <engine.exe> --output <output.exe>\n"
            "            --stub <loader_stub.obj>\n"
            "            [--embed name.dll=path\\to\\file.dll]...\n"
            "            [--preload barename]...\n");
        return 1;
    }

    // ============================================================
    // Step 1: Read engine.exe
    // ============================================================
    size_t engine_size;
    uint8_t *engine = (uint8_t *)read_whole_file(engine_path, &engine_size);

    IMAGE_DOS_HEADER *dos = (IMAGE_DOS_HEADER *)engine;
    if (dos->e_magic != 0x5A4D) die("not a valid DOS header (no MZ)");

    IMAGE_FILE_HEADER *pe = (IMAGE_FILE_HEADER *)(engine + dos->e_lfanew);
    if (pe->Signature != 0x00004550) die("not a valid PE file");
    if (pe->Machine != 0x8664) die("only x64 images are supported");

    IMAGE_OPTIONAL_HEADER64 *opt =
        (IMAGE_OPTIONAL_HEADER64 *)((uint8_t *)pe + sizeof(IMAGE_FILE_HEADER));
    if (opt->Magic != 0x20B) die("not a PE32+ image");

    int num_sec = pe->NumberOfSections;
    IMAGE_SECTION_HEADER *sec_hdrs =
        (IMAGE_SECTION_HEADER *)((uint8_t *)pe + sizeof(IMAGE_FILE_HEADER) +
                                  pe->SizeOfOptionalHeader);

    uint32_t sec_align = opt->SectionAlignment;
    uint32_t file_align = opt->FileAlignment;
    uint32_t orig_entry_rva = opt->AddressOfEntryPoint;

    printf("[1] Engine: %s\n", engine_path);
    printf("    Entry RVA: %#x  Sections: %d\n", orig_entry_rva, num_sec);

    // Locate import directory
    IMAGE_DATA_DIRECTORY *data_dirs =
        (IMAGE_DATA_DIRECTORY *)((uint8_t *)opt + sizeof(IMAGE_OPTIONAL_HEADER64));
    uint32_t import_rva = data_dirs[1].VirtualAddress;
    uint32_t import_size = data_dirs[1].Size;

    long import_fo = rva_to_offset(engine, sec_hdrs, num_sec, import_rva);
    if (import_fo < 0) die("cannot locate import directory");

    printf("    Import dir: RVA=%#x, Size=%u, FileOffset=%#lx\n",
           import_rva, import_size, import_fo);

    // ============================================================
    // Step 2: Walk import descriptors, identify VC++ runtime imports
    // ============================================================
    int num_imports = 0;
    IMAGE_IMPORT_DESCRIPTOR *imports = (IMAGE_IMPORT_DESCRIPTOR *)(engine + import_fo);

    while (1) {
        IMAGE_IMPORT_DESCRIPTOR *d = &imports[num_imports];
        if (d->OriginalFirstThunk == 0 && d->FirstThunk == 0 && d->Name == 0)
            break;
        num_imports++;
    }
    printf("[2] Import descriptors found: %d\n", num_imports);

    // Identify which import indices are VC++ runtime DLLs
    int vc_import_idx[MAX_FIXUP_DLLS];
    int vc_is_vc_runtime = 0;

    for (int i = 0; i < num_imports; i++) {
        long name_fo = rva_to_offset(engine, sec_hdrs, num_sec, imports[i].Name);
        if (name_fo < 0) continue;
        const char *dll_name = (const char *)(engine + name_fo);
        if (is_vc_runtime(dll_name)) {
            vc_import_idx[vc_is_vc_runtime++] = i;
            printf("    VC++ import #%d: %s\n", i, dll_name);
        }
    }

    if (vc_is_vc_runtime == 0)
        die("no VC++ runtime imports found — is this a valid Native Image exe?");

    // ============================================================
    // Step 3: Collect fixup entries for each VC++ import
    // ============================================================
    for (int k = 0; k < vc_is_vc_runtime; k++) {
        int idx = vc_import_idx[k];
        IMAGE_IMPORT_DESCRIPTOR *d = &imports[idx];

        // Get DLL name
        long name_fo = rva_to_offset(engine, sec_hdrs, num_sec, d->Name);
        const char *dll_name = (const char *)(engine + name_fo);

        DLL_FIXUP_INFO *fi = &fixup_dlls[fixup_dll_count++];
        memset(fi, 0, sizeof(*fi));
        strncpy(fi->dll_name, dll_name, sizeof(fi->dll_name) - 1);

        uint32_t int_rva = d->OriginalFirstThunk;
        uint32_t iat_rva_base = d->FirstThunk;
        long int_fo = rva_to_offset(engine, sec_hdrs, num_sec, int_rva);
        if (int_fo < 0) {
            fprintf(stderr, "WARNING: cannot locate INT for %s\n", dll_name);
            fixup_dll_count--;
            continue;
        }

        for (int j = 0; j < MAX_FIXUPS; j++) {
            uint64_t int_entry = read_u64le(engine + int_fo + j * 8);
            if (int_entry == 0) break;

            if (int_entry & 0x8000000000000000ULL) {
                fprintf(stderr, "WARNING: ordinal import in %s at index %d\n", dll_name, j);
                continue;
            }

            long iname_fo = rva_to_offset(engine, sec_hdrs, num_sec, (uint32_t)int_entry);
            if (iname_fo < 0) continue;

            const char *fname = (const char *)(engine + iname_fo + 2);
            FUNC_FIXUP *f = &fi->fixups[fi->fixup_count++];
            strncpy(f->name, fname, sizeof(f->name) - 1);
            f->name[sizeof(f->name) - 1] = '\0';
            f->iat_rva = iat_rva_base + j * 8;

            printf("      %s[%d] %s -> IAT RVA %#x\n",
                   dll_name, j, f->name, f->iat_rva);
        }
        printf("    %s fixup count: %d\n", dll_name, fi->fixup_count);
    }

    // ============================================================
    // Step 4: Read embedded DLL files, determine VC++ status
    // ============================================================
    printf("[3] Reading embedded DLL files...\n");
    for (int i = 0; i < embed_count; i++) {
        EMBED_INFO *e = &embed_list[i];
        e->data = (uint8_t *)read_whole_file(e->file_path, &e->data_size);
        printf("    %s: %zu bytes\n", e->name, e->data_size);

        // Check if this DLL matches a VC++ import we're stripping
        for (int k = 0; k < fixup_dll_count; k++) {
            if (_stricmp(e->name, fixup_dlls[k].dll_name) == 0) {
                e->is_vc = 1;
                e->flags |= EMBED_FLAG_PATCH_IAT;
                printf("      -> VC++ runtime, will patch IAT\n");
                break;
            }
        }
        // Mark all embedded DLLs for preload by default
        e->flags |= EMBED_FLAG_PRELOAD;
    }

    // Check if any --preload names need corresponding embed entries
    for (int p = 0; p < preload_count; p++) {
        int found = 0;
        for (int e = 0; e < embed_count; e++) {
            // Match preload name to DLL filename without extension
            char bare[128];
            strncpy(bare, embed_list[e].name, sizeof(bare) - 1);
            char *dot = strrchr(bare, '.');
            if (dot) *dot = '\0';
            if (_stricmp(preload_names[p], bare) == 0 ||
                _stricmp(preload_names[p], embed_list[e].name) == 0) {
                embed_list[e].flags |= EMBED_FLAG_PRELOAD;
                found = 1;
                break;
            }
        }
        if (!found)
            printf("    Note: --preload '%s' has no matching --embed\n", preload_names[p]);
    }

    // ============================================================
    // Step 4b: Resolve transitive VC++ dependencies
    // ============================================================
    printf("[4] Checking VC++ dependencies...\n");
    for (int k = 0; k < fixup_dll_count; k++) {
        for (int d = 0; VC_DEPENDENCIES[d][0]; d++) {
            if (_stricmp(fixup_dlls[k].dll_name, VC_DEPENDENCIES[d][0]) == 0) {
                const char *dep = VC_DEPENDENCIES[d][1];
                // Check if dependency is already embedded
                int already = 0;
                for (int e = 0; e < embed_count; e++) {
                    if (_stricmp(embed_list[e].name, dep) == 0) { already = 1; break; }
                }
                if (!already)
                    printf("    WARNING: %s depends on %s but it's not embedded!\n",
                           fixup_dlls[k].dll_name, dep);
                else
                    printf("    %s -> %s (dependency satisfied)\n",
                           fixup_dlls[k].dll_name, dep);
            }
        }
    }

    // ============================================================
    // Step 5: Remove VC++ entries from import directory
    // ============================================================
    printf("[5] Removing VC++ runtime entries from import directory...\n");

    IMAGE_IMPORT_DESCRIPTOR *new_imports = (IMAGE_IMPORT_DESCRIPTOR *)
        malloc((size_t)(num_imports + 1) * sizeof(IMAGE_IMPORT_DESCRIPTOR));
    if (!new_imports) die("out of memory");

    int new_idx = 0;
    for (int i = 0; i < num_imports; i++) {
        int is_vc = 0;
        for (int k = 0; k < vc_is_vc_runtime; k++)
            if (i == vc_import_idx[k]) { is_vc = 1; break; }
        if (is_vc) {
            printf("    Removing import #%d\n", i);
            continue;
        }
        memcpy(&new_imports[new_idx++], &imports[i], sizeof(IMAGE_IMPORT_DESCRIPTOR));
    }
    memset(&new_imports[new_idx], 0, sizeof(IMAGE_IMPORT_DESCRIPTOR));
    new_idx++;

    size_t new_import_bytes = (size_t)new_idx * sizeof(IMAGE_IMPORT_DESCRIPTOR);
    size_t old_import_bytes = (size_t)(num_imports + 1) * sizeof(IMAGE_IMPORT_DESCRIPTOR);

    memcpy(engine + import_fo, new_imports, new_import_bytes);
    if (new_import_bytes < old_import_bytes)
        memset(engine + import_fo + new_import_bytes, 0,
               old_import_bytes - new_import_bytes);
    data_dirs[1].Size = (uint32_t)new_import_bytes;
    free(new_imports);

    // ============================================================
    // Step 6: Read loader_stub.obj, extract .text section
    // ============================================================
    printf("[6] Reading loader stub object file...\n");
    size_t obj_size;
    uint8_t *obj = (uint8_t *)read_whole_file(stub_obj_path, &obj_size);

    if (obj_size < sizeof(COFF_FILE_HEADER)) die("stub obj too small");
    COFF_FILE_HEADER *coff = (COFF_FILE_HEADER *)obj;
    if (coff->Machine != 0x8664) die("stub obj is not x64 COFF");

    COFF_SECTION_HEADER *coff_sec = (COFF_SECTION_HEADER *)(obj + sizeof(COFF_FILE_HEADER));

    uint8_t *stub_code = NULL;
    uint32_t stub_code_size = 0;

    for (int i = 0; i < coff->NumberOfSections; i++) {
        char sname[9];
        memcpy(sname, coff_sec[i].Name, 8);
        sname[8] = '\0';
        int is_text = (strcmp(sname, ".text") == 0 ||
                       strncmp(sname, ".text$", 6) == 0);
        if (is_text && coff_sec[i].SizeOfRawData > 0) {
            stub_code = obj + coff_sec[i].PointerToRawData;
            stub_code_size = coff_sec[i].SizeOfRawData;
            printf("    .text section: '%s' size=%u\n", sname, stub_code_size);
            break;
        }
    }
    if (!stub_code) die("cannot find .text section in stub obj");
    if (stub_code_size > CONTEXT_OFFSET)
        dief("stub code too large for CONTEXT_OFFSET", NULL);

    // ============================================================
    // Step 7: Build .rmtldr section layout
    // ============================================================

    // Layout:
    //   [0] stub code (up to CONTEXT_OFFSET)
    //   [CONTEXT_OFFSET] RMT_LOADER_CONTEXT
    //   [CONTEXT_OFFSET + 48] stub function pointer slots (11 * 8 = 88 bytes)
    //   [...] FIXUP_ENTRY arrays for each VC++ DLL
    //   [...] DLL_FIXUP_TABLE array
    //   [...] DLL_EMBED_ENTRY array
    //   [...] DLL_PRELOAD_ENTRY array
    //   [...] name string table (8-byte aligned)
    //   [...] DLL binary data (page-aligned)

    printf("[7] Building .rmtldr section layout...\n");

    // ---- Name string table ----
    // Build in a temporary buffer, then assign offsets
    char name_buf[65536];
    int name_len = 0;

    // kernel32 function names
    uint32_t k32_name_offsets[16];
    for (int i = 0; i < NUM_KERNEL32_NAMES; i++) {
        k32_name_offsets[i] = name_len;
        int len = (int)strlen(kernel32_names[i]) + 1;
        memcpy(name_buf + name_len, kernel32_names[i], len);
        name_len += len;
    }

    // VC++ DLL fixup function names
    uint32_t fixup_name_offsets[MAX_FIXUP_DLLS][MAX_FIXUPS];
    for (int k = 0; k < fixup_dll_count; k++) {
        for (int j = 0; j < fixup_dlls[k].fixup_count; j++) {
            fixup_name_offsets[k][j] = name_len;
            int len = (int)strlen(fixup_dlls[k].fixups[j].name) + 1;
            memcpy(name_buf + name_len, fixup_dlls[k].fixups[j].name, len);
            name_len += len;
        }
    }

    // DLL filenames
    uint32_t embed_name_offsets[MAX_EMBED];
    for (int i = 0; i < embed_count; i++) {
        embed_name_offsets[i] = name_len;
        int len = (int)strlen(embed_list[i].name) + 1;
        memcpy(name_buf + name_len, embed_list[i].name, len);
        name_len += len;
    }

    // VC++ DLL fixup table header names
    uint32_t fixtab_name_offsets[MAX_FIXUP_DLLS];
    for (int k = 0; k < fixup_dll_count; k++) {
        fixtab_name_offsets[k] = name_len;
        int len = (int)strlen(fixup_dlls[k].dll_name) + 1;
        memcpy(name_buf + name_len, fixup_dlls[k].dll_name, len);
        name_len += len;
    }

    // Preload bare names
    uint32_t preload_name_offsets[MAX_EMBED];
    for (int p = 0; p < preload_count; p++) {
        preload_name_offsets[p] = name_len;
        int len = (int)strlen(preload_names[p]) + 1;
        memcpy(name_buf + name_len, preload_names[p], len);
        name_len += len;
    }

    printf("    Name table: %d bytes\n", name_len);

    // ---- Compute section offsets ----
    uint32_t cur = CONTEXT_OFFSET + sizeof(RMT_LOADER_CONTEXT);

    // Stub function pointers: 11 * 8 = 88 bytes, 8-byte aligned
    cur = (cur + 7) & ~7u;
    uint32_t stub_funcs_offset = cur;
    cur += NUM_KERNEL32_NAMES * 8;

    // FIXUP_ENTRY arrays (one per VC++ DLL, concatenated)
    uint32_t fixup_entries_offset = cur;
    uint32_t fixup_entry_offsets[MAX_FIXUP_DLLS];
    for (int k = 0; k < fixup_dll_count; k++) {
        fixup_entry_offsets[k] = cur;
        cur += fixup_dlls[k].fixup_count * sizeof(FIXUP_ENTRY);
    }

    // DLL_FIXUP_TABLE array
    uint32_t fixup_tables_offset = cur;
    cur += fixup_dll_count * sizeof(DLL_FIXUP_TABLE);

    // DLL_EMBED_ENTRY array
    uint32_t extract_offset = cur;
    cur += embed_count * sizeof(DLL_EMBED_ENTRY);

    // DLL_PRELOAD_ENTRY array
    uint32_t preload_offset = cur;
    cur += preload_count * sizeof(DLL_PRELOAD_ENTRY);

    // Name table (8-byte aligned)
    cur = (cur + 7) & ~7u;
    uint32_t name_table_offset = cur;
    cur += name_len;

    // DLL binary data (page-aligned to 0x1000)
    cur = ((cur + 0xFFF) / 0x1000) * 0x1000;
    uint32_t dll_data_offset = cur;

    // Compute DLL data offsets within the data area
    uint32_t embed_data_offsets[MAX_EMBED];
    uint32_t data_cur = 0;
    for (int i = 0; i < embed_count; i++) {
        embed_data_offsets[i] = dll_data_offset + data_cur;
        data_cur += (uint32_t)embed_list[i].data_size;
    }

    uint32_t rmtldr_virt_size = dll_data_offset + data_cur;

    // Raw size aligned to file alignment
    uint32_t rmtldr_raw_size = rmtldr_virt_size;
    if (file_align > 0)
        rmtldr_raw_size = ((rmtldr_raw_size + file_align - 1) / file_align) * file_align;

    // ---- Allocate and fill section buffer ----
    uint8_t *rmtldr = (uint8_t *)calloc(1, rmtldr_raw_size + file_align);
    if (!rmtldr) die("out of memory");

    // Copy stub code
    memcpy(rmtldr, stub_code, stub_code_size);

    // Write RMT_LOADER_CONTEXT
    RMT_LOADER_CONTEXT *ctx = (RMT_LOADER_CONTEXT *)(rmtldr + CONTEXT_OFFSET);
    memset(ctx, 0, sizeof(*ctx));
    ctx->magic          = RMT_MAGIC;
    ctx->version        = RMT_VERSION;
    ctx->extract_offset = extract_offset;
    ctx->extract_count  = embed_count;
    ctx->fixup_offset   = fixup_tables_offset;
    ctx->fixup_count    = fixup_dll_count;
    ctx->preload_offset = preload_offset;
    ctx->preload_count  = preload_count;
    ctx->name_table     = name_table_offset;
    ctx->orig_entry     = orig_entry_rva;
    ctx->stub_funcs     = stub_funcs_offset;

    // Write FIXUP_ENTRY arrays
    for (int k = 0; k < fixup_dll_count; k++) {
        FIXUP_ENTRY *entries = (FIXUP_ENTRY *)(rmtldr + fixup_entry_offsets[k]);
        for (int j = 0; j < fixup_dlls[k].fixup_count; j++) {
            entries[j].iat_rva     = fixup_dlls[k].fixups[j].iat_rva;
            entries[j].name_offset = fixup_name_offsets[k][j];
        }
    }

    // Write DLL_FIXUP_TABLE array
    for (int k = 0; k < fixup_dll_count; k++) {
        DLL_FIXUP_TABLE *ft = (DLL_FIXUP_TABLE *)(rmtldr + fixup_tables_offset
                                                   + k * sizeof(DLL_FIXUP_TABLE));
        ft->dll_name_offset = fixtab_name_offsets[k];
        ft->fixup_offset    = fixup_entry_offsets[k];
        ft->fixup_count     = fixup_dlls[k].fixup_count;
    }

    // Write DLL_EMBED_ENTRY array
    for (int i = 0; i < embed_count; i++) {
        DLL_EMBED_ENTRY *ee = (DLL_EMBED_ENTRY *)(rmtldr + extract_offset
                                                   + i * sizeof(DLL_EMBED_ENTRY));
        ee->name_offset = embed_name_offsets[i];
        ee->data_offset = embed_data_offsets[i];
        ee->data_size   = (uint32_t)embed_list[i].data_size;
        ee->flags       = embed_list[i].flags;
    }

    // Write DLL_PRELOAD_ENTRY array
    for (int p = 0; p < preload_count; p++) {
        DLL_PRELOAD_ENTRY *pe = (DLL_PRELOAD_ENTRY *)(rmtldr + preload_offset
                                                       + p * sizeof(DLL_PRELOAD_ENTRY));
        pe->name_offset = preload_name_offsets[p];
    }

    // Write name table
    memcpy(rmtldr + name_table_offset, name_buf, name_len);

    // Write DLL binary data
    for (int i = 0; i < embed_count; i++)
        memcpy(rmtldr + embed_data_offsets[i], embed_list[i].data, embed_list[i].data_size);

    printf("    .rmtldr virtual size: %u bytes\n", rmtldr_virt_size);
    printf("    Extract table: %d entries\n", embed_count);
    printf("    Fixup tables: %d DLLs\n", fixup_dll_count);
    printf("    Preload list: %d entries\n", preload_count);

    // ============================================================
    // Step 8: Find new section RVA and file offset
    // ============================================================
    IMAGE_SECTION_HEADER *last_sec = &sec_hdrs[num_sec - 1];
    uint32_t new_sec_rva = last_sec->VirtualAddress + last_sec->VirtualSize;
    if (sec_align > 0)
        new_sec_rva = ((new_sec_rva + sec_align - 1) / sec_align) * sec_align;

    uint32_t engine_file_size = (uint32_t)engine_size;
    uint32_t new_sec_raw_ptr = engine_file_size;
    if (file_align > 0)
        new_sec_raw_ptr = ((new_sec_raw_ptr + file_align - 1) / file_align) * file_align;

    printf("[8] New section: RVA=%#x, FileOffset=%#x\n", new_sec_rva, new_sec_raw_ptr);

    // ============================================================
    // Step 9: Write output exe
    // ============================================================
    printf("[9] Writing output: %s\n", output_path);

    size_t output_size = (size_t)new_sec_raw_ptr + rmtldr_raw_size;
    uint8_t *output = (uint8_t *)calloc(1, output_size);
    if (!output) die("out of memory");

    memcpy(output, engine, engine_size);
    memcpy(output + new_sec_raw_ptr, rmtldr, rmtldr_raw_size);

    // Update PE headers in output
    IMAGE_DOS_HEADER *out_dos = (IMAGE_DOS_HEADER *)output;
    IMAGE_FILE_HEADER *out_pe = (IMAGE_FILE_HEADER *)(output + out_dos->e_lfanew);
    IMAGE_OPTIONAL_HEADER64 *out_opt =
        (IMAGE_OPTIONAL_HEADER64 *)((uint8_t *)out_pe + sizeof(IMAGE_FILE_HEADER));
    IMAGE_SECTION_HEADER *out_sec =
        (IMAGE_SECTION_HEADER *)((uint8_t *)out_pe + sizeof(IMAGE_FILE_HEADER) +
                                  out_pe->SizeOfOptionalHeader);
    IMAGE_DATA_DIRECTORY *out_dd =
        (IMAGE_DATA_DIRECTORY *)((uint8_t *)out_opt + sizeof(IMAGE_OPTIONAL_HEADER64));

    // Add .rmtldr section header
    IMAGE_SECTION_HEADER *new_sec = &out_sec[out_pe->NumberOfSections];
    memset(new_sec, 0, sizeof(*new_sec));
    memcpy(new_sec->Name, ".rmtldr", 7);
    new_sec->VirtualSize      = rmtldr_virt_size;
    new_sec->VirtualAddress   = new_sec_rva;
    new_sec->SizeOfRawData    = rmtldr_raw_size;
    new_sec->PointerToRawData = new_sec_raw_ptr;
    new_sec->Characteristics  = 0xE0000020;  // CODE | EXECUTE | READ | WRITE

    out_opt->AddressOfEntryPoint = new_sec_rva;
    out_pe->NumberOfSections++;

    uint32_t new_size_image = new_sec_rva + rmtldr_virt_size;
    if (sec_align > 0)
        new_size_image = ((new_size_image + sec_align - 1) / sec_align) * sec_align;
    out_opt->SizeOfImage = new_size_image;

    // Update import directory size in output
    out_dd[1].VirtualAddress = import_rva;
    out_dd[1].Size = (uint32_t)new_import_bytes;

    // Write
    FILE *fout = fopen(output_path, "wb");
    if (!fout) dief("cannot create output '%s'", output_path);
    if (fwrite(output, 1, output_size, fout) != output_size)
        die("failed to write all output data");
    fclose(fout);

    printf("\nDONE: %s (%zu bytes)\n", output_path, output_size);
    printf("  VC++ runtime DLLs removed from imports:\n");
    for (int k = 0; k < fixup_dll_count; k++)
        printf("    %s (%d functions)\n", fixup_dlls[k].dll_name, fixup_dlls[k].fixup_count);
    printf("  Embedded DLLs: %d total\n", embed_count);
    for (int i = 0; i < embed_count; i++)
        printf("    %s (%zu bytes)\n", embed_list[i].name, embed_list[i].data_size);
    printf("  Entry: %#x -> .rmtldr (%#x)\n", orig_entry_rva, new_sec_rva);

    free(engine);
    free(obj);
    free(rmtldr);
    free(output);
    for (int i = 0; i < embed_count; i++) free(embed_list[i].data);
    return 0;
}
