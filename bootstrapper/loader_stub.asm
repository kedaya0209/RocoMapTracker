; loader_stub.asm - PE 入口桩代码 (v2, 匹配 pe_patch.c RMT_LOADER_CONTEXT)
; 编译: ml64 /c /Fo loader_stub.obj loader_stub.asm
;
; 运行时 13 阶段:
;   1.  PEB -> exe基址, kernel32基址
;   2.  查找 .rmtldr 段
;   3.  验证 context magic (0x4C544D52)
;   4.  设置 name table 基址
;   5.  解析 kernel32 导出 -> GetProcAddress
;   6.  解析剩余 10 个 kernel32 函数
;   7.  获取 exe 路径 + 构建 dll/ 目录路径
;   8.  CreateDirectoryW(dll/)
;   9.  AddDllDirectory + SetDefaultDllDirectories
;   10. 提取所有 DLL 到 dll/
;   11. LoadLibrary VC++ DLL + 修补 IAT
;   12. 预加载 JVM/JavaFX DLL (两轮, 解决依赖顺序)
;   13. jmp 原始入口
option casemap:none

; ============================================================
; 常量定义 (必须与 pe_patch.c 一致)
; ============================================================
CONTEXT_OFFSET          equ 1000h
RMT_MAGIC               equ 4C544D52h

; Context 字段偏移 (RMT_LOADER_CONTEXT, 44 bytes, pack(1))
CTX_MAGIC               equ 0
CTX_VERSION             equ 4
CTX_EXTRACT_OFFSET      equ 8
CTX_EXTRACT_COUNT       equ 12
CTX_FIXUP_OFFSET        equ 16
CTX_FIXUP_COUNT         equ 20
CTX_PRELOAD_OFFSET      equ 24
CTX_PRELOAD_COUNT       equ 28
CTX_NAME_TABLE          equ 32
CTX_ORIG_ENTRY          equ 36
CTX_STUB_FUNCS          equ 40

; Stub 函数指针槽位 (.rmtldr + 0x1030, 11 * 8 = 88 bytes)
STUB_LoadLibraryW               equ 1030h
STUB_GetProcAddress             equ 1038h
STUB_VirtualProtect             equ 1040h
STUB_GetModuleFileNameW         equ 1048h
STUB_CreateDirectoryW           equ 1050h
STUB_CreateFileW                equ 1058h
STUB_WriteFile                  equ 1060h
STUB_CloseHandle                equ 1068h
STUB_FlushFileBuffers           equ 1070h
STUB_SetDefaultDllDirectories   equ 1078h
STUB_AddDllDirectory            equ 1080h

; Name table 中 kernel32 函数名字符串偏移 (由 pe_patch.c 写入顺序决定)
STR_LoadLibraryW                equ 0
STR_GetProcAddress              equ 13
STR_VirtualProtect              equ 28
STR_GetModuleFileNameW          equ 43
STR_CreateDirectoryW            equ 62
STR_CreateFileW                 equ 79
STR_WriteFile                   equ 91
STR_CloseHandle                 equ 101
STR_FlushFileBuffers            equ 113
STR_SetDefaultDllDirectories    equ 130
STR_AddDllDirectory             equ 155

; DLL_EMBED_ENTRY 字段偏移 (16 bytes, pack(1))
EMBED_NAME_OFFSET       equ 0
EMBED_DATA_OFFSET       equ 4
EMBED_DATA_SIZE         equ 8
EMBED_FLAGS             equ 12

EMBED_FLAG_PRELOAD      equ 1
EMBED_FLAG_PATCH_IAT    equ 2

; DLL_FIXUP_TABLE 字段偏移 (12 bytes, pack(1))
FIXTAB_DLL_NAME         equ 0
FIXTAB_FIXUP_OFF        equ 4
FIXTAB_FIXUP_COUNT      equ 8

; FIXUP_ENTRY 字段偏移 (8 bytes, pack(1))
FIXUP_IAT_RVA           equ 0
FIXUP_NAME_OFF          equ 4

; Win32 常量
GENERIC_WRITE                   equ 40000000h
CREATE_ALWAYS                   equ 2
FILE_ATTRIBUTE_NORMAL           equ 80h
INVALID_HANDLE_VALUE            equ -1
PAGE_READWRITE                  equ 4
LOAD_LIBRARY_SEARCH_USER_DIRS   equ 400h
LOAD_LIBRARY_SEARCH_DEFAULT_DIRS equ 1000h
MAX_PATH                        equ 260

; ============================================================
; 辅助宏
; ============================================================

; 修补一个 IAT 槽: rsi = FIXUP_ENTRY, r12 = DLL句柄, rbp = name table, r14 = exe基址
fixup_one MACRO
    ; GetProcAddress(r12, name_table + entry.name_offset)
    mov ecx, [rsi+FIXUP_NAME_OFF]
    lea rdx, [rbp+rcx]
    mov rcx, r12
    call qword ptr [r15+STUB_GetProcAddress]
    test rax, rax
    jnz @F
    inc dword ptr [rsp+96]       ; count unresolved
    add rsi, 8
    jmp _fixup_next
  @@:
    ; VirtualProtect(exe_base + iat_rva, 8, PAGE_READWRITE, &oldProtect)
    ; 保存函数地址到栈 (r10 会被 VirtualProtect 破坏)
	mov [rsp+72], rax
    mov ecx, [rsi+FIXUP_IAT_RVA]
    add rcx, r14
    mov edx, 8
    mov r8d, PAGE_READWRITE
    lea r9, [rsp+48]
    call qword ptr [r15+STUB_VirtualProtect]
    ; 从栈恢复函数地址并写入 IAT
	mov r10, [rsp+72]
	mov ecx, [rsi+FIXUP_IAT_RVA]
	mov [r14+rcx], r10
    add rsi, 8
ENDM

_TEXT SEGMENT 'CODE'

PUBLIC entry_point

entry_point PROC
    push r15
    push r14
    push r13
    push r12
    push rbp
    push rbx
    push rdi
    push rsi
    sub rsp, 1288

    ; 栈布局 (rsp 在 sub 之后保持稳定):
    ; rsp+0:    shadow space (32 bytes)
    ; rsp+32:   API arg5 / temp (only during calls)
    ; rsp+40:   API arg6 / bytesWritten (DWORD)
    ; rsp+48:   API arg7 / oldProtect (DWORD)
    ; rsp+56:   save_rbx (permanent slot)
    ; rsp+64:   save_rdi (permanent slot)
    ; rsp+72:   hFile / save_r10 (temp slot)
    ; rsp+80:   dll_dir_len (dll/ 宽字符串字节长度)
    ; rsp+88:   fixup_loop_counter
    ; rsp+96:   unresolved_count (DWORD) + pad
    ; rsp+128:  dll_dir_wide (260 WCHARs = 520 bytes)
    ; rsp+648:  full_path_wide (260 WCHARs = 520 bytes)

    ; ----------------------------------------------------------
    ; Phase 1: PEB -> exe基址, kernel32基址
    ; ----------------------------------------------------------
    mov rax, gs:[60h]           ; PEB
    mov rax, [rax+18h]          ; PEB_LDR_DATA
    mov rax, [rax+10h]          ; InLoadOrderModuleList
    mov r14, [rax+30h]          ; r14 = exe基址 (DllBase)
    mov rbx, rax

_find_k32:
    mov rcx, [rax+60h]          ; BaseDllName.Buffer
    test rcx, rcx
    jz _next_k32
    cmp dword ptr [rcx], 0045004Bh
    jne _next_k32
    cmp dword ptr [rcx+4], 004E0052h
    jne _next_k32
    cmp dword ptr [rcx+8], 004C0045h
    jne _next_k32
    cmp dword ptr [rcx+12], 00320033h
    jne _next_k32
    mov r13, [rax+30h]          ; r13 = kernel32基址
    jmp _k32_found

_next_k32:
    mov rax, [rax]
    cmp rax, rbx
    jne _find_k32
    mov eax, 1
    jmp _abort

_k32_found:

    ; ----------------------------------------------------------
    ; Phase 2: 查找 .rmtldr 段
    ; ----------------------------------------------------------
    mov eax, [r14+3Ch]
    lea rdi, [r14+rax]          ; rdi = PE header

    movzx ecx, word ptr [rdi+6]  ; NumberOfSections
    movzx eax, word ptr [rdi+14h] ; SizeOfOptionalHeader
    lea rsi, [rdi+18h+rax]       ; rsi = 第一个 section header

    mov r8, 0072646C746D722Eh    ; ".rmtldr\0"
_find_sec:
    mov rax, [rsi]
    cmp rax, r8
    je _found_sec
    add rsi, 40
    dec ecx
    jnz _find_sec
    mov eax, 2
    jmp _abort

_found_sec:
    mov eax, [rsi+0Ch]
    add rax, r14
    mov r15, rax                ; r15 = .rmtldr 段基址

    ; ----------------------------------------------------------
    ; Phase 3: 验证 context magic
    ; ----------------------------------------------------------
    cmp dword ptr [r15+CONTEXT_OFFSET+CTX_MAGIC], RMT_MAGIC
    je _magic_ok
    mov eax, 3
    jmp _abort
_magic_ok:

    ; ----------------------------------------------------------
    ; Phase 4: 设置 name table 基址
    ; ----------------------------------------------------------
    mov eax, [r15+CONTEXT_OFFSET+CTX_NAME_TABLE]
    lea rbp, [r15+rax]          ; rbp = name table base

    ; ----------------------------------------------------------
    ; Phase 5: 解析 kernel32 导出 -> GetProcAddress
    ; ----------------------------------------------------------
    mov eax, [r13+3Ch]
    lea rdi, [r13+rax]

    mov eax, [rdi+88h]
    test eax, eax
    jz _abort
    lea r10, [r13+rax]          ; r10 = IMAGE_EXPORT_DIRECTORY

    mov eax, [r10+20h]
    lea rbx, [r13+rax]          ; rbx = names
    mov eax, [r10+24h]
    lea r11, [r13+rax]          ; r11 = ordinals
    mov eax, [r10+1Ch]
    lea r12, [r13+rax]          ; r12 = functions
    mov ecx, [r10+18h]          ; NumberOfNames

    xor edx, edx
    cld
_find_gpa:
    mov eax, [rbx+rdx*4]
    add rax, r13

    push rcx
    push rdx
    push rbx

    mov rdi, rax
    lea rsi, [rbp+STR_GetProcAddress]
    mov ecx, 14
    repe cmpsb
    je _gpa_found

    pop rbx
    pop rdx
    pop rcx
    inc edx
    cmp edx, ecx
    jb _find_gpa
    mov eax, 4
    jmp _abort

_gpa_found:
    pop rbx
    pop rdx
    pop rcx

    movzx eax, word ptr [r11+rdx*2]
    mov eax, [r12+rax*4]
    add rax, r13
    mov [r15+STUB_GetProcAddress], rax

    ; ----------------------------------------------------------
    ; Phase 6: 解析剩余 10 个 kernel32 函数
    ; ----------------------------------------------------------
    ; 使用 call/pop 获取当前 RIP 以避免 COFF 重定位
    ; (lea rdi, [_k32_table] 会生成 REL32 重定位，pe_patch.c 不处理)
    call _get_rip
_get_rip:
    pop rdi
    add rdi, OFFSET _k32_table - OFFSET _get_rip
    mov ebx, 10
_k32_loop:
    mov ecx, [rdi]
    mov r8d, [rdi+4]
    mov [rsp+56], rbx            ; 保存循环计数器
    mov [rsp+64], rdi            ; 保存表指针
    lea rdx, [rbp+rcx]
    mov rcx, r13
    call qword ptr [r15+STUB_GetProcAddress]
    mov rbx, [rsp+56]
    mov rdi, [rsp+64]
    test rax, rax
    jz _abort
    mov r8d, [rdi+4]
    mov [r15+r8], rax
    add rdi, 8
    dec ebx
    jnz _k32_loop

    ; ----------------------------------------------------------
    ; Phase 7: GetModuleFileNameW + 构建 dll/ 目录路径
    ; ----------------------------------------------------------
    xor ecx, ecx
    lea rdx, [rsp+128]
    mov r8d, MAX_PATH
    call qword ptr [r15+STUB_GetModuleFileNameW]
    test rax, rax
    jz _abort

    ; 查找宽字符串末尾
    lea rcx, [rsp+128]
_find_wend:
    cmp word ptr [rcx], 0
    je _found_wend
    add rcx, 2
    jmp _find_wend

_found_wend:
    ; 从末尾往回找最后一个 '\' (U+005C)
_find_slash:
    sub rcx, 2
    cmp word ptr [rcx], 5Ch
    jne _find_slash

    ; 写入 "dll\0" 在 '\' 之后
    add rcx, 2
    mov dword ptr [rcx], 006C0064h
    mov dword ptr [rcx+4], 0000006Ch

    ; 保存 dll_dir 字节长度 (含末尾 null)
    ; "dll\0" = 4 宽字符 = 8 字节，加到偏移上得到完整路径长度
    lea rax, [rsp+128]
    sub rcx, rax
    add rcx, 8
    mov [rsp+80], rcx

    ; ----------------------------------------------------------
    ; Phase 8: CreateDirectoryW(dll/)
    ; ----------------------------------------------------------
    lea rcx, [rsp+128]
    call qword ptr [r15+STUB_CreateDirectoryW]
    ; 忽略返回值 (目录可能已存在)

    ; ----------------------------------------------------------
    ; Phase 9: AddDllDirectory + SetDefaultDllDirectories
    ; ----------------------------------------------------------
    lea rcx, [rsp+128]
    call qword ptr [r15+STUB_AddDllDirectory]
    test rax, rax
    jz _abort

    mov ecx, LOAD_LIBRARY_SEARCH_USER_DIRS or LOAD_LIBRARY_SEARCH_DEFAULT_DIRS
    call qword ptr [r15+STUB_SetDefaultDllDirectories]

    ; ----------------------------------------------------------
    ; Phase 10: 提取所有 DLL 到 dll/
    ; ----------------------------------------------------------
    mov eax, [r15+CONTEXT_OFFSET+CTX_EXTRACT_COUNT]
    test eax, eax
    jz _fixup_phase

    mov ebx, eax
    mov eax, [r15+CONTEXT_OFFSET+CTX_EXTRACT_OFFSET]
    lea rdi, [r15+rax]          ; rdi = DLL_EMBED_ENTRY 数组

_extract_loop:
    ; --- 构建完整路径: full_path = dll_dir + "\" + DLL名称 ---
    mov rcx, [rsp+80]           ; dll_dir 字节长度
    sub rcx, 2                  ; 不含末尾 null
    lea rsi, [rsp+128]          ; 源: dll_dir (wide)
    lea rdx, [rsp+648]          ; 目标: full_path (wide)
_extract_copy_dir:
    movzx eax, word ptr [rsi]
    mov [rdx], ax
    add rsi, 2
    add rdx, 2
    sub rcx, 2
    jnz _extract_copy_dir

    ; 写入 "\" (U+005C)
    mov word ptr [rdx], 5Ch
    add rdx, 2

    ; 转换 ASCII DLL 名称 -> wide
    mov ecx, [rdi+EMBED_NAME_OFFSET]
    lea rsi, [rbp+rcx]
_extract_convert_name:
    movzx eax, byte ptr [rsi]
    mov [rdx], ax
    add rsi, 1
    add rdx, 2
    test eax, eax
    jnz _extract_convert_name

    ; --- CreateFileW(full_path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, NORMAL, NULL) ---
    mov [rsp+56], rbx           ; save rbx
    mov [rsp+64], rdi           ; save rdi
    lea rcx, [rsp+648]
    mov edx, GENERIC_WRITE
    xor r8d, r8d
    xor r9d, r9d
    mov dword ptr [rsp+32], CREATE_ALWAYS
    mov dword ptr [rsp+40], FILE_ATTRIBUTE_NORMAL
    mov qword ptr [rsp+48], 0   ; hTemplateFile = NULL
    call qword ptr [r15+STUB_CreateFileW]
    mov rbx, [rsp+56]           ; restore rbx
    mov rdi, [rsp+64]           ; restore rdi

    cmp rax, INVALID_HANDLE_VALUE
    je _extract_next

    ; --- WriteFile(hFile, data, size, &bytesWritten, NULL) ---
    mov r10, rax                ; hFile
    mov ecx, [rdi+EMBED_DATA_OFFSET]
    lea rdx, [r15+rcx]          ; DLL data
    mov r8d, [rdi+EMBED_DATA_SIZE]

    mov [rsp+56], rbx           ; save rbx
    mov [rsp+64], rdi           ; save rdi
    mov [rsp+72], r10           ; save hFile
    mov rcx, r10
    lea r9, [rsp+40]            ; &bytesWritten
    mov qword ptr [rsp+32], 0   ; lpOverlapped = NULL
    call qword ptr [r15+STUB_WriteFile]
    mov rbx, [rsp+56]
    mov rdi, [rsp+64]
    mov r10, [rsp+72]

    ; --- FlushFileBuffers(hFile) ---
    mov [rsp+56], rbx
    mov [rsp+64], rdi
    mov [rsp+72], r10           ; save hFile (r10 will be clobbered)
    mov rcx, r10
    call qword ptr [r15+STUB_FlushFileBuffers]
    mov rbx, [rsp+56]
    mov rdi, [rsp+64]
    mov r10, [rsp+72]           ; restore hFile

    ; --- CloseHandle(hFile) ---
    mov [rsp+56], rbx
    mov [rsp+64], rdi
    mov rcx, r10
    call qword ptr [r15+STUB_CloseHandle]
    mov rbx, [rsp+56]
    mov rdi, [rsp+64]

_extract_next:
    add rdi, 16                 ; sizeof(DLL_EMBED_ENTRY)
    dec ebx
    jnz _extract_loop

    ; ----------------------------------------------------------
    ; Phase 11: LoadLibrary VC++ DLL + 修补 IAT
    ; ----------------------------------------------------------
_fixup_phase:
    mov dword ptr [rsp+96], 0   ; unresolved count = 0

    mov eax, [r15+CONTEXT_OFFSET+CTX_FIXUP_COUNT]
    test eax, eax
    jz _preload_phase

    mov ebx, eax
    mov eax, [r15+CONTEXT_OFFSET+CTX_FIXUP_OFFSET]
    lea rdi, [r15+rax]          ; rdi = DLL_FIXUP_TABLE 数组

_fixtab_loop:
    ; --- 构建 DLL 完整路径: full_path = dll_dir + "\" + DLL名称 ---
    mov rcx, [rsp+80]
    sub rcx, 2
    lea rsi, [rsp+128]
    lea rdx, [rsp+648]
_fixtab_copy_dir:
    movzx eax, word ptr [rsi]
    mov [rdx], ax
    add rsi, 2
    add rdx, 2
    sub rcx, 2
    jnz _fixtab_copy_dir

    mov word ptr [rdx], 5Ch
    add rdx, 2

    mov ecx, [rdi+FIXTAB_DLL_NAME]
    lea rsi, [rbp+rcx]
_fixtab_convert_name:
    movzx eax, byte ptr [rsi]
    mov [rdx], ax
    add rsi, 1
    add rdx, 2
    test eax, eax
    jnz _fixtab_convert_name

    ; --- LoadLibraryW(full_path) ---
    mov [rsp+56], rbx
    mov [rsp+64], rdi
    lea rcx, [rsp+648]
    call qword ptr [r15+STUB_LoadLibraryW]
    mov rbx, [rsp+56]
    mov rdi, [rsp+64]

    test rax, rax
    jz _abort                   ; VC++ DLL 加载失败 -> 致命

    mov r12, rax                ; r12 = DLL 句柄

    ; --- 遍历 FIXUP_ENTRY 并修补 IAT ---
    mov ecx, [rdi+FIXTAB_FIXUP_COUNT]
    mov [rsp+88], rcx           ; loop counter
    mov eax, [rdi+FIXTAB_FIXUP_OFF]
    lea rsi, [r15+rax]          ; rsi = FIXUP_ENTRY 数组

_fixup_loop:
    fixup_one
_fixup_next:
    dec qword ptr [rsp+88]
    jnz _fixup_loop

    add rdi, 12                 ; sizeof(DLL_FIXUP_TABLE)
    dec ebx
    jnz _fixtab_loop

    ; ----------------------------------------------------------
    ; Phase 12: 预加载 JVM/JavaFX DLL — 两轮加载
    ; 第一轮加载所有 DLL; 第二轮补上因依赖未就绪而失败的 DLL
    ; ----------------------------------------------------------
_preload_phase:
    mov dword ptr [rsp+96], 0   ; pass = 0

_preload_pass:
    mov eax, [r15+CONTEXT_OFFSET+CTX_EXTRACT_COUNT]
    test eax, eax
    jz _done

    mov ebx, eax
    mov eax, [r15+CONTEXT_OFFSET+CTX_EXTRACT_OFFSET]
    lea rdi, [r15+rax]          ; rdi = DLL_EMBED_ENTRY 数组

_preload_loop:
    test dword ptr [rdi+EMBED_FLAGS], EMBED_FLAG_PRELOAD
    jz _preload_next

    ; --- 构建完整路径 ---
    mov rcx, [rsp+80]
    sub rcx, 2
    lea rsi, [rsp+128]
    lea rdx, [rsp+648]
_preload_copy_dir:
    movzx eax, word ptr [rsi]
    mov [rdx], ax
    add rsi, 2
    add rdx, 2
    sub rcx, 2
    jnz _preload_copy_dir

    mov word ptr [rdx], 5Ch
    add rdx, 2

    mov ecx, [rdi+EMBED_NAME_OFFSET]
    lea rsi, [rbp+rcx]
_preload_convert:
    movzx eax, byte ptr [rsi]
    mov [rdx], ax
    add rsi, 1
    add rdx, 2
    test eax, eax
    jnz _preload_convert

    ; --- LoadLibraryW(full_path) ---
    mov [rsp+56], rbx
    mov [rsp+64], rdi
    lea rcx, [rsp+648]
    call qword ptr [r15+STUB_LoadLibraryW]
    mov rbx, [rsp+56]
    mov rdi, [rsp+64]
    ; 忽略加载失败 (非致命)

_preload_next:
    add rdi, 16
    dec ebx
    jnz _preload_loop

    ; 第一轮完成 → 第二轮 (补上因依赖未就绪而失败的 DLL)
    inc dword ptr [rsp+96]
    cmp dword ptr [rsp+96], 2
    jne _preload_pass

    ; ----------------------------------------------------------
    ; Phase 13: 跳转到原始入口点
    ; ----------------------------------------------------------
_done:
    mov ecx, [r15+CONTEXT_OFFSET+CTX_ORIG_ENTRY]
    add rcx, r14

    add rsp, 1288
    pop rsi
    pop rdi
    pop rbx
    pop rbp
    pop r12
    pop r13
    pop r14
    pop r15
    jmp rcx

_abort:
    add rsp, 1288
    pop rsi
    pop rdi
    pop rbx
    pop rbp
    pop r12
    pop r13
    pop r14
    pop r15
    ret

entry_point ENDP

; kernel32 函数解析表 (name_str_offset, stub_slot_offset)
_k32_table:
    dd STR_LoadLibraryW, STUB_LoadLibraryW
    dd STR_VirtualProtect, STUB_VirtualProtect
    dd STR_GetModuleFileNameW, STUB_GetModuleFileNameW
    dd STR_CreateDirectoryW, STUB_CreateDirectoryW
    dd STR_CreateFileW, STUB_CreateFileW
    dd STR_WriteFile, STUB_WriteFile
    dd STR_CloseHandle, STUB_CloseHandle
    dd STR_FlushFileBuffers, STUB_FlushFileBuffers
    dd STR_SetDefaultDllDirectories, STUB_SetDefaultDllDirectories
    dd STR_AddDllDirectory, STUB_AddDllDirectory

_TEXT ENDS
END
