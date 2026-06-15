// pe_patch.c - Build-time PE post-processor for RocoMapTracker
//
// Strips vcruntime140.dll/vcruntime140_1.dll from the import directory,
// embeds both DLLs plus a MASM64 loader stub as a new .rmtldl section,
// and changes the entry point to the stub.
//
// Compile (VS x64 prompt): cl /nologo /O1 /GS- /Fo pe_patch.obj /Fe pe_patch.exe pe_patch.c
// Run:   pe_patch --engine RocoMapTracker.engine.exe --output RocoMapTracker.exe ^
//                 --vcr140 vcruntime140.dll --vcr140_1 vcruntime140_1.dll ^
//                 --stub loader_stub.obj

#define _CRT_SECURE_NO_WARNINGS
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <errno.h>

// ============================================================
// PE structures (minimal, portable)
// ============================================================

#pragma pack(push, 1)
typedef struct {
    uint16_t e_magic;    // MZ
    uint16_t e_cblp;
    uint16_t e_cp;
    uint16_t e_crlc;
    uint16_t e_cparhdr;
    uint16_t e_minalloc;
    uint16_t e_maxalloc;
    uint16_t e_ss;
    uint16_t e_sp;
    uint16_t e_csum;
    uint16_t e_ip;
    uint16_t e_cs;
    uint16_t e_lfarlc;
    uint16_t e_ovno;
    uint16_t e_res[4];
    uint16_t e_oemid;
    uint16_t e_oeminfo;
    uint16_t e_res2[10];
    uint32_t e_lfanew;   // offset to PE signature
} IMAGE_DOS_HEADER;

typedef struct {
    uint32_t Signature;             // PE\0\0
    uint16_t Machine;
    uint16_t NumberOfSections;
    uint32_t TimeDateStamp;
    uint32_t PointerToSymbolTable;
    uint32_t NumberOfSymbols;
    uint16_t SizeOfOptionalHeader;
    uint16_t Characteristics;
} IMAGE_FILE_HEADER;

typedef struct {
    uint16_t Magic;                  // 0x20b = PE32+
    uint8_t  MajorLinkerVersion;
    uint8_t  MinorLinkerVersion;
    uint32_t SizeOfCode;
    uint32_t SizeOfInitializedData;
    uint32_t SizeOfUninitializedData;
    uint32_t AddressOfEntryPoint;
    uint32_t BaseOfCode;
    uint64_t ImageBase;
    uint32_t SectionAlignment;
    uint32_t FileAlignment;
    uint16_t MajorOperatingSystemVersion;
    uint16_t MinorOperatingSystemVersion;
    uint16_t MajorImageVersion;
    uint16_t MinorImageVersion;
    uint16_t MajorSubsystemVersion;
    uint16_t MinorSubsystemVersion;
    uint32_t Win32VersionValue;
    uint32_t SizeOfImage;
    uint32_t SizeOfHeaders;
    uint32_t CheckSum;
    uint16_t Subsystem;
    uint16_t DllCharacteristics;
    uint64_t SizeOfStackReserve;
    uint64_t SizeOfStackCommit;
    uint64_t SizeOfHeapReserve;
    uint64_t SizeOfHeapCommit;
    uint32_t LoaderFlags;
    uint32_t NumberOfRvaAndSizes;
    // IMAGE_DATA_DIRECTORY DataDirectory[NumberOfRvaAndSizes]
} IMAGE_OPTIONAL_HEADER64;

typedef struct {
    uint32_t VirtualAddress;
    uint32_t Size;
} IMAGE_DATA_DIRECTORY;

typedef struct {
    uint8_t  Name[8];
    uint32_t VirtualSize;
    uint32_t VirtualAddress;
    uint32_t SizeOfRawData;
    uint32_t PointerToRawData;
    uint32_t PointerToRelocations;
    uint32_t PointerToLinenumbers;
    uint16_t NumberOfRelocations;
    uint16_t NumberOfLinenumbers;
    uint32_t Characteristics;
} IMAGE_SECTION_HEADER;

typedef struct {
    uint32_t OriginalFirstThunk;  // INT RVA
    uint32_t TimeDateStamp;
    uint32_t ForwarderChain;
    uint32_t Name;                // DLL name RVA
    uint32_t FirstThunk;          // IAT RVA
} IMAGE_IMPORT_DESCRIPTOR;

// RmtLoaderContext passed to the stub
typedef struct {
    uint32_t vcr140_offset;      // vcruntime140 data offset from .rmtldr base
    uint32_t vcr140_size;        // vcruntime140 data size
    uint32_t vcr140_1_offset;    // vcruntime140_1 data offset
    uint32_t vcr140_1_size;      // vcruntime140_1 data size
    uint32_t fixup_offset;       // vcruntime140 fixup table offset
    uint32_t fixup_count;        // vcruntime140 fixup entries
    uint32_t fixup_1_offset;     // vcruntime140_1 fixup table offset
    uint32_t fixup_1_count;      // vcruntime140_1 fixup entries
    uint32_t name_table;         // name string table offset
    uint32_t orig_entry;         // original entry point RVA
} RMT_LOADER_CONTEXT;

// Fixup entry in the table
typedef struct {
    uint32_t iat_rva;            // RVA of IAT slot to patch
    uint32_t name_offset;        // offset into name table for function name
} FIXUP_ENTRY;

// COFF file header (for loader_stub.obj)
typedef struct {
    uint16_t Machine;
    uint16_t NumberOfSections;
    uint32_t TimeDateStamp;
    uint32_t PointerToSymbolTable;
    uint32_t NumberOfSymbols;
    uint16_t SizeOfOptionalHeader;
    uint16_t Characteristics;
} COFF_FILE_HEADER;

typedef struct {
    char     Name[8];
    uint32_t VirtualSize;
    uint32_t VirtualAddress;
    uint32_t SizeOfRawData;
    uint32_t PointerToRawData;
    uint32_t PointerToRelocations;
    uint32_t PointerToLinenumbers;
    uint16_t NumberOfRelocations;
    uint16_t NumberOfLinenumbers;
    uint32_t Characteristics;
} COFF_SECTION_HEADER;
#pragma pack(pop)

// Stub layout constants (must match loader_stub.asm)
enum {
    CONTEXT_OFFSET      = 0x800,
    STUB_FUNCPTR_OFFSET = 0x828,   // 8 qword function pointer slots (CONTEXT_OFFSET + 40)
    STUB_FUNCPTR_SIZE   = 64,      // 8 * 8
    FIXUP_TABLE_OFFSET  = 0x868,   // STUB_FUNCPTR_OFFSET + STUB_FUNCPTR_SIZE
    DLL_DATA_ALIGN      = 0x1000,  // DLL data page alignment
};

// Number of built-in strings the stub needs
#define NUM_STUB_NAMES  8

// Fixed name string offsets within the name table (must match loader_stub.asm)
// "GetProcAddress\0"     = 15 bytes -> offset 0
// "LoadLibraryW\0"       = 13 bytes -> offset 15
// "CreateFileW\0"        = 12 bytes -> offset 28
// "WriteFile\0"          = 10 bytes -> offset 40
// "CloseHandle\0"        = 12 bytes -> offset 50
// "VirtualProtect\0"     = 15 bytes -> offset 62
// "GetModuleFileNameW\0" = 19 bytes -> offset 77
// "FlushFileBuffers\0"   = 17 bytes -> offset 96
static const char *stub_names[] = {
    "GetProcAddress",
    "LoadLibraryW",
    "CreateFileW",
    "WriteFile",
    "CloseHandle",
    "VirtualProtect",
    "GetModuleFileNameW",
    "FlushFileBuffers",
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

static void write_u32le(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v);
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
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

// ============================================================
// Section helper — RVA to file offset
// ============================================================
static long rva_to_offset(const uint8_t *pe_start,
                           const IMAGE_SECTION_HEADER *sec_hdrs,
                           int num_sec, uint32_t rva)
{
    for (int i = 0; i < num_sec; i++) {
        uint32_t rva_start = sec_hdrs[i].VirtualAddress;
        uint32_t rva_end   = rva_start + sec_hdrs[i].VirtualSize;
        if (rva >= rva_start && rva < rva_end) {
            return (long)(rva - rva_start + sec_hdrs[i].PointerToRawData);
        }
    }
    return -1;
}

// ============================================================
// Main patching logic
// ============================================================
int main(int argc, char **argv) {
    const char *engine_path   = NULL;
    const char *output_path   = NULL;
    const char *vcr140_path    = NULL;
    const char *vcr140_1_path  = NULL;
    const char *stub_obj_path  = NULL;

    // Parse args
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--engine") == 0 && i + 1 < argc)
            engine_path = argv[++i];
        else if (strcmp(argv[i], "--output") == 0 && i + 1 < argc)
            output_path = argv[++i];
        else if (strcmp(argv[i], "--vcr140") == 0 && i + 1 < argc)
            vcr140_path = argv[++i];
        else if (strcmp(argv[i], "--vcr140_1") == 0 && i + 1 < argc)
            vcr140_1_path = argv[++i];
        else if (strcmp(argv[i], "--stub") == 0 && i + 1 < argc)
            stub_obj_path = argv[++i];
    }

    if (!engine_path || !output_path || !vcr140_path || !vcr140_1_path || !stub_obj_path) {
        fprintf(stderr,
            "Usage: pe_patch --engine <engine.exe> --output <output.exe>\n"
            "            --vcr140 <vcruntime140.dll> --vcr140_1 <vcruntime140_1.dll>\n"
            "            --stub <loader_stub.obj>\n");
        return 1;
    }

    // ============================================================
    // Step 1: Read engine.exe
    // ============================================================
    size_t engine_size;
    uint8_t *engine = (uint8_t *)read_whole_file(engine_path, &engine_size);

    // DOS header
    IMAGE_DOS_HEADER *dos = (IMAGE_DOS_HEADER *)engine;
    if (dos->e_magic != 0x5A4D) die("not a valid DOS header (no MZ)");

    IMAGE_FILE_HEADER *pe = (IMAGE_FILE_HEADER *)(engine + dos->e_lfanew);
    if (pe->Signature != 0x00004550) die("not a valid PE file (no PE signature)");

    if (pe->Machine != 0x8664) die("only x64 (AMD64) images are supported");

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
    // Step 2: Walk import descriptors, collect vcruntime info
    // ============================================================
    int num_imports = 0;
    IMAGE_IMPORT_DESCRIPTOR *imports = (IMAGE_IMPORT_DESCRIPTOR *)(engine + import_fo);

    // Count
    while (1) {
        IMAGE_IMPORT_DESCRIPTOR *d = &imports[num_imports];
        if (d->OriginalFirstThunk == 0 && d->FirstThunk == 0 &&
            d->Name == 0) break;
        num_imports++;
    }
    printf("[2] Import descriptors found: %d\n", num_imports);

    // Read DLL names and identify vcruntime indices
    int vcr140_idx = -1, vcr140_1_idx = -1;
    for (int i = 0; i < num_imports; i++) {
        long name_fo = rva_to_offset(engine, sec_hdrs, num_sec, imports[i].Name);
        if (name_fo < 0) continue;
        const char *dll_name = (const char *)(engine + name_fo);
        if (_stricmp(dll_name, "VCRUNTIME140.dll") == 0) vcr140_idx = i;
        if (_stricmp(dll_name, "VCRUNTIME140_1.dll") == 0) vcr140_1_idx = i;
    }

    if (vcr140_idx < 0) die("VCRUNTIME140.dll not found in imports");
    if (vcr140_1_idx < 0) die("VCRUNTIME140_1.dll not found in imports");
    printf("    VCRUNTIME140.dll at index %d\n", vcr140_idx);
    printf("    VCRUNTIME140_1.dll at index %d\n", vcr140_1_idx);

    // ============================================================
    // Step 3: Collect fixup entries (function names + IAT RVAs)
    // ============================================================
    typedef struct {
        char name[80];
        uint32_t iat_rva;
    } FUNC_FIXUP;

    FUNC_FIXUP vcr140_fixups[64];
    int vcr140_fixup_count = 0;

    {
        IMAGE_IMPORT_DESCRIPTOR *d = &imports[vcr140_idx];
        uint32_t int_rva = d->OriginalFirstThunk;
        uint32_t iat_rva_base = d->FirstThunk;
        long int_fo = rva_to_offset(engine, sec_hdrs, num_sec, int_rva);
        if (int_fo < 0) die("cannot locate VCRUNTIME140 INT");

        for (int j = 0; ; j++) {
            uint64_t int_entry = read_u64le(engine + int_fo + j * 8);
            if (int_entry == 0) break;

            if (int_entry & 0x8000000000000000ULL) {
                // Ordinal import — unsupported for vcruntime
                fprintf(stderr, "WARNING: ordinal import in VCRUNTIME140 at index %d\n", j);
                continue;
            }

            // Import by name: read hint + name from IMAGE_IMPORT_BY_NAME
            long iname_fo = rva_to_offset(engine, sec_hdrs, num_sec, (uint32_t)int_entry);
            if (iname_fo < 0) {
                fprintf(stderr, "WARNING: cannot resolve import name at RVA %#llx\n",
                        (unsigned long long)int_entry);
                continue;
            }
            uint16_t hint = read_u16le(engine + iname_fo);
            (void)hint;
            const char *fname = (const char *)(engine + iname_fo + 2);

            FUNC_FIXUP *f = &vcr140_fixups[vcr140_fixup_count++];
            strncpy(f->name, fname, sizeof(f->name) - 1);
            f->name[sizeof(f->name) - 1] = '\0';
            f->iat_rva = iat_rva_base + j * 8;

            printf("      vcr140[%d] %s -> IAT RVA %#x\n",
                   j, f->name, f->iat_rva);
        }
    }
    printf("    VCRUNTIME140 fixup count: %d\n", vcr140_fixup_count);

    FUNC_FIXUP vcr140_1_fixups[8];
    int vcr140_1_fixup_count = 0;

    {
        IMAGE_IMPORT_DESCRIPTOR *d = &imports[vcr140_1_idx];
        uint32_t int_rva = d->OriginalFirstThunk;
        uint32_t iat_rva_base = d->FirstThunk;
        long int_fo = rva_to_offset(engine, sec_hdrs, num_sec, int_rva);
        if (int_fo < 0) die("cannot locate VCRUNTIME140_1 INT");

        for (int j = 0; ; j++) {
            uint64_t int_entry = read_u64le(engine + int_fo + j * 8);
            if (int_entry == 0) break;

            if (int_entry & 0x8000000000000000ULL) {
                fprintf(stderr, "WARNING: ordinal import in VCRUNTIME140_1 at index %d\n", j);
                continue;
            }

            long iname_fo = rva_to_offset(engine, sec_hdrs, num_sec, (uint32_t)int_entry);
            if (iname_fo < 0) continue;
            const char *fname = (const char *)(engine + iname_fo + 2);

            FUNC_FIXUP *f = &vcr140_1_fixups[vcr140_1_fixup_count++];
            strncpy(f->name, fname, sizeof(f->name) - 1);
            f->name[sizeof(f->name) - 1] = '\0';
            f->iat_rva = iat_rva_base + j * 8;

            printf("      vcr140_1[%d] %s -> IAT RVA %#x\n",
                   j, f->name, f->iat_rva);
        }
    }
    printf("    VCRUNTIME140_1 fixup count: %d\n", vcr140_1_fixup_count);

    if (vcr140_fixup_count == 0)
        die("no import names found for VCRUNTIME140.dll");
    if (vcr140_1_fixup_count == 0)
        die("no import names found for VCRUNTIME140_1.dll");

    // ============================================================
    // Step 4: Remove vcruntime entries from import directory
    // ============================================================
    printf("[3] Removing vcruntime entries from import directory...\n");

    IMAGE_IMPORT_DESCRIPTOR *new_imports = (IMAGE_IMPORT_DESCRIPTOR *)
        malloc((size_t)(num_imports + 1) * sizeof(IMAGE_IMPORT_DESCRIPTOR));
    if (!new_imports) die("out of memory");

    int new_idx = 0;
    for (int i = 0; i < num_imports; i++) {
        if (i == vcr140_idx || i == vcr140_1_idx) {
            printf("    Removing import #%d\n", i);
            continue;
        }
        memcpy(&new_imports[new_idx++], &imports[i], sizeof(IMAGE_IMPORT_DESCRIPTOR));
    }
    // Terminator
    memset(&new_imports[new_idx], 0, sizeof(IMAGE_IMPORT_DESCRIPTOR));
    new_idx++;

    size_t new_import_bytes = (size_t)new_idx * sizeof(IMAGE_IMPORT_DESCRIPTOR);
    size_t old_import_bytes = (size_t)(num_imports + 1) * sizeof(IMAGE_IMPORT_DESCRIPTOR);

    // Overwrite in place
    memcpy(engine + import_fo, new_imports, new_import_bytes);
    // Zero out the remainder of the old import directory
    if (new_import_bytes < old_import_bytes) {
        memset(engine + import_fo + new_import_bytes, 0,
               old_import_bytes - new_import_bytes);
    }

    // Update data directory size
    data_dirs[1].Size = (uint32_t)new_import_bytes;
    free(new_imports);

    // ============================================================
    // Step 5: Read vcruntime DLL binaries
    // ============================================================
    printf("[4] Reading DLL binaries...\n");
    size_t dll140_size;
    uint8_t *dll140 = (uint8_t *)read_whole_file(vcr140_path, &dll140_size);
    printf("    vcruntime140.dll: %zu bytes\n", dll140_size);

    size_t dll140_1_size;
    uint8_t *dll140_1 = (uint8_t *)read_whole_file(vcr140_1_path, &dll140_1_size);
    printf("    vcruntime140_1.dll: %zu bytes\n", dll140_1_size);

    // ============================================================
    // Step 6: Read loader_stub.obj, extract .text section
    // ============================================================
    printf("[5] Reading loader stub object file...\n");
    size_t obj_size;
    uint8_t *obj = (uint8_t *)read_whole_file(stub_obj_path, &obj_size);

    if (obj_size < sizeof(COFF_FILE_HEADER))
        die("stub obj file too small");

    COFF_FILE_HEADER *coff = (COFF_FILE_HEADER *)obj;
    if (coff->Machine != 0x8664)
        die("stub obj is not x64 COFF");

    COFF_SECTION_HEADER *coff_sec = (COFF_SECTION_HEADER *)(obj + sizeof(COFF_FILE_HEADER));

    uint8_t *stub_code = NULL;
    uint32_t stub_code_size = 0;

    for (int i = 0; i < coff->NumberOfSections; i++) {
        char sname[9];
        memcpy(sname, coff_sec[i].Name, 8);
        sname[8] = '\0';

        // MASM generates .text$mn for _TEXT SEGMENT; match any .text* variant
        int is_text = (strcmp(sname, ".text") == 0 ||
                       strcmp(sname, "text") == 0 ||
                       strncmp(sname, ".text$", 6) == 0);

        if (is_text && coff_sec[i].SizeOfRawData > 0) {
            stub_code = obj + coff_sec[i].PointerToRawData;
            stub_code_size = coff_sec[i].SizeOfRawData;
            printf("    Found .text section: '%s' offset=%u, size=%u\n",
                   sname, coff_sec[i].PointerToRawData, stub_code_size);
            break;
        }
    }
    if (!stub_code) die("cannot find .text section in stub obj file");
    printf("    Stub code size: %u bytes\n", stub_code_size);

    // ============================================================
    // Step 7: Build .rmtldr section data
    // ============================================================
    printf("[6] Building new .rmtldr section...\n");

    // Build name string table
    char name_table_buf[4096];
    int name_table_len = 0;

    for (int i = 0; i < NUM_STUB_NAMES; i++) {
        int len = (int)strlen(stub_names[i]) + 1;
        memcpy(name_table_buf + name_table_len, stub_names[i], len);
        name_table_len += len;
    }

    // Add vcruntime140 function names
    uint32_t vcr140_name_offsets[64];
    for (int i = 0; i < vcr140_fixup_count; i++) {
        vcr140_name_offsets[i] = name_table_len;
        int len = (int)strlen(vcr140_fixups[i].name) + 1;
        memcpy(name_table_buf + name_table_len, vcr140_fixups[i].name, len);
        name_table_len += len;
    }

    // Add vcruntime140_1 function names
    uint32_t vcr140_1_name_offsets[8];
    for (int i = 0; i < vcr140_1_fixup_count; i++) {
        vcr140_1_name_offsets[i] = name_table_len;
        int len = (int)strlen(vcr140_1_fixups[i].name) + 1;
        memcpy(name_table_buf + name_table_len, vcr140_1_fixups[i].name, len);
        name_table_len += len;
    }

    printf("    Name table size: %d bytes\n", name_table_len);

    // Build fixup tables
    FIXUP_ENTRY vcr140_fixup_table[64];
    for (int i = 0; i < vcr140_fixup_count; i++) {
        vcr140_fixup_table[i].iat_rva = vcr140_fixups[i].iat_rva;
        vcr140_fixup_table[i].name_offset = vcr140_name_offsets[i];
    }

    FIXUP_ENTRY vcr140_1_fixup_table[8];
    for (int i = 0; i < vcr140_1_fixup_count; i++) {
        vcr140_1_fixup_table[i].iat_rva = vcr140_1_fixups[i].iat_rva;
        vcr140_1_fixup_table[i].name_offset = vcr140_1_name_offsets[i];
    }

    // Calculate offsets within .rmtldr section
    uint32_t stub_code_padded = stub_code_size;
    if (stub_code_padded > CONTEXT_OFFSET) {
        printf("    Warning: stub code (%u bytes) exceeds context offset (%u)\n",
               stub_code_padded, CONTEXT_OFFSET);
    }

    // Fixup table end = start + vcr140 entries + vcr140_1 entries
    uint32_t fixup_end = FIXUP_TABLE_OFFSET +
        (uint32_t)((vcr140_fixup_count + vcr140_1_fixup_count) * sizeof(FIXUP_ENTRY));
    // Name table: 8-byte aligned after fixups
    uint32_t name_table_offset = (fixup_end + 7) & ~7u;

    // DLL data: page-aligned after name table
    uint32_t dll_data_offset = name_table_offset + (uint32_t)name_table_len;
    dll_data_offset = ((dll_data_offset + DLL_DATA_ALIGN - 1) / DLL_DATA_ALIGN) * DLL_DATA_ALIGN;

    // Total .rmtldr virtual size
    uint32_t rmtldr_virt_size = dll_data_offset +
        (uint32_t)dll140_size + (uint32_t)dll140_1_size;

    // Raw size aligned to file alignment
    uint32_t rmtldr_raw_size = rmtldr_virt_size;
    if (file_align > 0) {
        rmtldr_raw_size = ((rmtldr_raw_size + file_align - 1) / file_align) * file_align;
    }

    // Allocate section buffer
    uint8_t *rmtldr_data = (uint8_t *)calloc(1, rmtldr_raw_size + file_align);
    if (!rmtldr_data) die("out of memory");

    // Copy stub code
    memcpy(rmtldr_data, stub_code, stub_code_size);

    // Build RmtLoaderContext at CONTEXT_OFFSET
    RMT_LOADER_CONTEXT *ctx = (RMT_LOADER_CONTEXT *)(rmtldr_data + CONTEXT_OFFSET);
    memset(ctx, 0, sizeof(*ctx));

    ctx->vcr140_offset   = dll_data_offset;
    ctx->vcr140_size     = (uint32_t)dll140_size;
    ctx->vcr140_1_offset = dll_data_offset + (uint32_t)dll140_size;
    ctx->vcr140_1_size   = (uint32_t)dll140_1_size;
    ctx->fixup_offset    = FIXUP_TABLE_OFFSET;
    ctx->fixup_count     = vcr140_fixup_count;
    ctx->fixup_1_offset  = FIXUP_TABLE_OFFSET +
                           (uint32_t)(vcr140_fixup_count * sizeof(FIXUP_ENTRY));
    ctx->fixup_1_count   = vcr140_1_fixup_count;
    ctx->name_table      = name_table_offset;
    ctx->orig_entry      = orig_entry_rva;

    // Copy fixup tables
    memcpy(rmtldr_data + FIXUP_TABLE_OFFSET, vcr140_fixup_table,
           vcr140_fixup_count * sizeof(FIXUP_ENTRY));
    memcpy(rmtldr_data + ctx->fixup_1_offset, vcr140_1_fixup_table,
           vcr140_1_fixup_count * sizeof(FIXUP_ENTRY));

    // Copy name table
    memcpy(rmtldr_data + name_table_offset, name_table_buf, name_table_len);

    // Copy DLL data
    memcpy(rmtldr_data + dll_data_offset, dll140, dll140_size);
    memcpy(rmtldr_data + dll_data_offset + dll140_size, dll140_1, dll140_1_size);

    printf("    .rmtldr virtual size: %u bytes, raw size: %u bytes\n",
           rmtldr_virt_size, rmtldr_raw_size);
    printf("    Name table at +%#x (%u bytes), DLL data at +%#x\n",
           name_table_offset, name_table_len, dll_data_offset);

    // ============================================================
    // Step 8: Find new section RVA and file offset
    // ============================================================
    // The new section goes after the last existing section
    IMAGE_SECTION_HEADER *last_sec = &sec_hdrs[num_sec - 1];
    uint32_t new_sec_rva = last_sec->VirtualAddress + last_sec->VirtualSize;
    // Round up to section alignment
    if (sec_align > 0) {
        new_sec_rva = ((new_sec_rva + sec_align - 1) / sec_align) * sec_align;
    }

    uint32_t new_sec_raw_ptr = last_sec->PointerToRawData + last_sec->SizeOfRawData;
    if (file_align > 0) {
        new_sec_raw_ptr = ((new_sec_raw_ptr + file_align - 1) / file_align) * file_align;
    }

    // The new section must not overlap existing file data
    uint32_t engine_file_size = (uint32_t)engine_size;
    uint32_t engine_file_aligned = engine_file_size;
    if (file_align > 0) {
        engine_file_aligned = ((engine_file_aligned + file_align - 1) / file_align) * file_align;
    }
    if (new_sec_raw_ptr < engine_file_aligned) {
        new_sec_raw_ptr = engine_file_aligned;
    }

    printf("[7] Adding .rmtldr section header...\n");
    printf("    New section RVA: %#x, File offset: %#x\n",
           new_sec_rva, new_sec_raw_ptr);

    // ============================================================
    // Step 9: Write output exe
    // ============================================================
    printf("[8] Writing output: %s\n", output_path);

    // Total output = all existing data (padded) + new section
    size_t output_size = (size_t)new_sec_raw_ptr + rmtldr_raw_size;
    uint8_t *output = (uint8_t *)calloc(1, output_size);
    if (!output) die("out of memory");

    // Copy engine data
    memcpy(output, engine, engine_size);

    // Copy .rmtldr section data after engine data
    memcpy(output + new_sec_raw_ptr, rmtldr_data, rmtldr_raw_size);

    // Update PE headers in the output copy
    IMAGE_DOS_HEADER *out_dos = (IMAGE_DOS_HEADER *)output;
    IMAGE_FILE_HEADER *out_pe = (IMAGE_FILE_HEADER *)(output + out_dos->e_lfanew);
    IMAGE_OPTIONAL_HEADER64 *out_opt =
        (IMAGE_OPTIONAL_HEADER64 *)((uint8_t *)out_pe + sizeof(IMAGE_FILE_HEADER));
    IMAGE_SECTION_HEADER *out_sec =
        (IMAGE_SECTION_HEADER *)((uint8_t *)out_pe + sizeof(IMAGE_FILE_HEADER) +
                                  out_pe->SizeOfOptionalHeader);

    // Add new section header
    IMAGE_SECTION_HEADER *new_sec = &out_sec[out_pe->NumberOfSections];
    memset(new_sec, 0, sizeof(IMAGE_SECTION_HEADER));
    memcpy(new_sec->Name, ".rmtldr", 7);  // must match loader_stub.asm
    new_sec->VirtualSize      = rmtldr_virt_size;
    new_sec->VirtualAddress   = new_sec_rva;
    new_sec->SizeOfRawData    = rmtldr_raw_size;
    new_sec->PointerToRawData = new_sec_raw_ptr;
    new_sec->Characteristics  = 0xE0000020;  // CODE | MEM_EXECUTE | MEM_READ | MEM_WRITE

    // Update entry point to .rmtldr section start
    out_opt->AddressOfEntryPoint = new_sec_rva;
    printf("    Entry point changed: %#x -> %#x\n", orig_entry_rva, new_sec_rva);

    // Update NumberOfSections
    out_pe->NumberOfSections++;

    // Update SizeOfImage
    uint32_t new_size_image = new_sec_rva + rmtldr_virt_size;
    if (sec_align > 0) {
        new_size_image = ((new_size_image + sec_align - 1) / sec_align) * sec_align;
    }
    out_opt->SizeOfImage = new_size_image;
    printf("    SizeOfImage: %#x -> %#x\n", opt->SizeOfImage, new_size_image);

    // Update data directory for import dir (since we modified it)
    IMAGE_DATA_DIRECTORY *out_data_dirs =
        (IMAGE_DATA_DIRECTORY *)((uint8_t *)out_opt + sizeof(IMAGE_OPTIONAL_HEADER64));

    // The import dir modifications were done in-place in the engine buffer.
    // We need to update the import dir size in the output.
    out_data_dirs[1].VirtualAddress = import_rva;  // same location
    out_data_dirs[1].Size = (uint32_t)new_import_bytes;

    // Write the output file
    FILE *fout = fopen(output_path, "wb");
    if (!fout) dief("cannot create output '%s'", output_path);

    size_t written = fwrite(output, 1, output_size, fout);
    if (written != output_size) die("failed to write all output data");
    fclose(fout);

    printf("\nDONE: %s created successfully (%zu bytes)\n", output_path, output_size);
    printf("  - VCRUNTIME140.dll (%d functions) removed from imports\n", vcr140_fixup_count);
    printf("  - VCRUNTIME140_1.dll (%d functions) removed from imports\n", vcr140_1_fixup_count);
    printf("  - .rmtldr section added at RVA %#x\n", new_sec_rva);
    printf("  - Entry point -> .rmtldr (%#x)\n", new_sec_rva);
    printf("  - vcruntime140.dll embedded (%zu bytes)\n", dll140_size);
    printf("  - vcruntime140_1.dll embedded (%zu bytes)\n", dll140_1_size);

    free(engine);
    free(dll140);
    free(dll140_1);
    free(obj);
    free(rmtldr_data);
    free(output);
    return 0;
}
