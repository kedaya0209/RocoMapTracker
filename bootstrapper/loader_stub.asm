; loader_stub.asm - PE 入口桩代码 (完全展开, MASM 宏 + 匿名标签)
; 编译: ml64 /c /Fo loader_stub.obj loader_stub.asm
option casemap:none

CONTEXT_OFFSET        equ 800h
CTX_VCR140_OFFSET    equ 0
CTX_VCR140_SIZE      equ 4
CTX_VCR140_1_OFFSET  equ 8
CTX_VCR140_1_SIZE    equ 12
CTX_FIXUP_OFFSET     equ 16
CTX_FIXUP_COUNT      equ 20
CTX_FIXUP_1_OFFSET   equ 24
CTX_FIXUP_1_COUNT    equ 28
CTX_NAME_TABLE       equ 32
CTX_ORIG_ENTRY       equ 36
CTX_SIZE             equ 40

DEBUG_PHASE             equ 7C0h  ; debug: phase counter (writable by stub)
STUB_GetProcAddress      equ 828h
STUB_LoadLibraryW        equ 830h
STUB_CreateFileW         equ 838h
STUB_WriteFile           equ 840h
STUB_CloseHandle         equ 848h
STUB_VirtualProtect      equ 850h
STUB_GetModuleFileNameW  equ 858h
STUB_FlushFileBuffers    equ 860h
STUB_SetDllDirectoryW    equ 868h

STR_GetProcAddress       equ 0
STR_LoadLibraryW         equ 15
STR_CreateFileW          equ 28
STR_WriteFile            equ 40
STR_CloseHandle          equ 50
STR_VirtualProtect       equ 62
STR_GetModuleFileNameW   equ 77
STR_FlushFileBuffers     equ 96
STR_SetDllDirectoryW     equ 113

; 修复一个 IAT 槽
fixup_entry MACRO
    mov ebx, [rsi]
    mov eax, [rsi+4]
    lea rdx, [rbp+rax]
    mov rcx, r12
    call qword ptr [r15+STUB_GetProcAddress]
    test rax, rax
    jnz @F
    inc dword ptr [r15 + DEBUG_PHASE + 4]  ; debug: count unresolved
    jmp @F
  @@:
    mov r13, rax
    mov rcx, r14
    add rcx, rbx
    mov rdx, 8
    mov r8d, 4
    lea r9, [rsp+56]
    call qword ptr [r15+STUB_VirtualProtect]
    mov rcx, r14
    add rcx, rbx
    mov [rcx], r13
  @@:
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
    sub rsp, 1032

    ; rsp+56: oldProtect (DWORD)
    ; rsp+64: ucrtbase (QWORD)

    ; ----------------------------------------------------------
    ; Phase 1: PEB -> exe基址, kernel32基址, ucrtbase基址
    ; ----------------------------------------------------------
    mov rax, gs:[60h]
    mov rax, [rax+18h]
    mov rax, [rax+10h]
    mov r14, [rax+30h]          ; r14 = exe基址
    mov rbx, rax

_find_k32:
    mov rcx, [rax+60h]
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
    mov rsi, rbx
    mov rax, [rbx]
_find_ucrt:
    mov rcx, [rax+60h]
    test rcx, rcx
    jz _next_ucrt
    cmp dword ptr [rcx], 00630075h
    jne _next_ucrt
    cmp dword ptr [rcx+4], 00740072h
    jne _next_ucrt
    cmp dword ptr [rcx+8], 00610062h
    jne _next_ucrt
    cmp dword ptr [rcx+12], 00650073h
    jne _next_ucrt
    mov rax, [rax+30h]
    mov [rsp+64], rax
    jmp _ucrt_found

_next_ucrt:
    mov rax, [rax]
    cmp rax, rsi
    jne _find_ucrt
    xor eax, eax
    mov [rsp+64], rax

_ucrt_found:

    ; ----------------------------------------------------------
    ; Phase 2: 查找 .rmtldr 段
    ; ----------------------------------------------------------
    mov eax, [r14+3Ch]
    lea rdi, [r14+rax]

    movzx ecx, word ptr [rdi+6]
    movzx eax, word ptr [rdi+20]
    lea rsi, [rdi+24+rax]

    mov r8, 0072646C746D722Eh
_find_sec:
    mov rax, [rsi]
    cmp rax, r8
    je _found_sec
    add rsi, 40
    dec ecx
    jnz _find_sec
    mov eax, 1
    jmp _abort

_found_sec:
    mov eax, [rsi+0Ch]
    add rax, r14
    mov r15, rax                ; r15 = .rmtldr 段基址
    mov dword ptr [r15 + DEBUG_PHASE], 2  ; debug: Phase 2 done
    mov dword ptr [r15 + DEBUG_PHASE + 4], 0  ; debug: clear fail counter

    ; ----------------------------------------------------------
    ; Phase 3: kernel32 导出表 -> GetProcAddress
    ; ----------------------------------------------------------
    mov eax, [r13+3Ch]
    lea rdi, [r13+rax]

    mov eax, [rdi+88h]
    test eax, eax
    jz _abort
    lea r10, [r13+rax]

    mov eax, [r10+20h]
    lea rbx, [r13+rax]
    mov eax, [r10+24h]
    lea r11, [r13+rax]
    mov eax, [r10+1Ch]
    lea r12, [r13+rax]
    mov ecx, [r10+18h]

    mov edi, [r15+CONTEXT_OFFSET+CTX_NAME_TABLE]
    lea rbp, [r15+rdi]          ; rbp = name table base

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
    mov eax, 2
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
    ; Phase 4: 解析 VirtualProtect
    ; ----------------------------------------------------------
    mov rcx, r13
    lea rdx, [rbp+STR_VirtualProtect]
    call qword ptr [r15+STUB_GetProcAddress]
    test rax, rax
    jz _abort
    mov [r15+STUB_VirtualProtect], rax
    mov dword ptr [r15 + DEBUG_PHASE], 4  ; debug: Phase 4 done

    ; ----------------------------------------------------------
    ; Phase 5: 修补 VCRUNTIME140 IAT (14个函数, 展开)
    ; ----------------------------------------------------------
    mov rdi, r13                ; 保存 kernel32 基址 (fixup_entry 会覆盖 r13, rdi 非 volatile)
    mov r12, [rsp+64]
    test r12, r12
    jz _abort

    mov eax, [r15+CONTEXT_OFFSET+CTX_FIXUP_OFFSET]
    lea rsi, [r15+rax]

    fixup_entry  ; [0] _local_unwind
    fixup_entry  ; [1] __std_exception_copy
    fixup_entry  ; [2] __C_specific_handler
    fixup_entry  ; [3] memcmp
    fixup_entry  ; [4] wcsrchr
    fixup_entry  ; [5] __current_exception_context
    fixup_entry  ; [6] wcsstr
    fixup_entry  ; [7] wcschr
    fixup_entry  ; [8] memset
    fixup_entry  ; [9] memcpy
    fixup_entry  ; [10] __std_exception_destroy
    fixup_entry  ; [11] _CxxThrowException
    fixup_entry  ; [12] memmove
    fixup_entry  ; [13] __current_exception
    mov dword ptr [r15 + DEBUG_PHASE], 5  ; debug: Phase 5 done

    ; ----------------------------------------------------------
    ; Phase 6: 修补 VCRUNTIME140_1 IAT (1个函数)
    ; ----------------------------------------------------------
    mov eax, [r15+CONTEXT_OFFSET+CTX_FIXUP_1_OFFSET]
    lea rsi, [r15+rax]

    mov eax, [r15+CONTEXT_OFFSET+CTX_FIXUP_1_COUNT]
    test eax, eax
    jz _setdll

    fixup_entry  ; [0] __CxxFrameHandler4

    ; ----------------------------------------------------------
    ; Phase 7: SetDllDirectoryW(exeDir\dll\)
    ; ----------------------------------------------------------
_setdll:
    mov dword ptr [r15 + DEBUG_PHASE], 6  ; debug: Phase 6 done
    ; 解析 GetModuleFileNameW
    mov rcx, rdi
    lea rdx, [rbp + STR_GetModuleFileNameW]
    call qword ptr [r15 + STUB_GetProcAddress]
    test rax, rax
    jz _abort
    mov [r15 + STUB_GetModuleFileNameW], rax

    ; 解析 SetDllDirectoryW
    mov rcx, rdi
    lea rdx, [rbp + STR_SetDllDirectoryW]
    call qword ptr [r15 + STUB_GetProcAddress]
    test rax, rax
    jz _abort
    mov [r15 + STUB_SetDllDirectoryW], rax

    ; GetModuleFileNameW(NULL, buf, 260)
    xor ecx, ecx
    lea rdx, [rsp + 128]
    mov r8d, 260
    call qword ptr [r15 + STUB_GetModuleFileNameW]
    test rax, rax
    jz _abort

    ; 找到宽字符串末尾
    lea rcx, [rsp + 128]
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

    ; rcx 指向最后一个 '\', 后面写入 "dll\0"
    add rcx, 2
    mov dword ptr [rcx], 006C0064h
    mov dword ptr [rcx + 4], 0000006Ch

    ; SetDllDirectoryW(buf)
    lea rcx, [rsp + 128]
    call qword ptr [r15 + STUB_SetDllDirectoryW]
    mov dword ptr [r15 + DEBUG_PHASE], 7  ; debug: Phase 7 done

    ; ----------------------------------------------------------
    ; Phase 8: 跳转到原始入口点
    ; ----------------------------------------------------------
_done:
    mov dword ptr [r15 + DEBUG_PHASE], 8  ; debug: Phase 8 (entering original code)
    mov ecx, [r15+CONTEXT_OFFSET+CTX_ORIG_ENTRY]
    add rcx, r14

    add rsp, 1032
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
    add rsp, 1032
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

_TEXT ENDS
END
