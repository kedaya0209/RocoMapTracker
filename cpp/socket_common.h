// socket_common.h — Shared TCP message protocol helpers for C++ subprocesses.
// Used by capture_main.cpp and sift_match_main.cpp.
//
// Provides: big-endian serialization, HELLO handshake builder, blocking
// send/recv helpers for the [4B msgType BE][4B bodyLen BE][body] framing.

#ifndef SOCKET_COMMON_H
#define SOCKET_COMMON_H

#include <winsock2.h>
#include <cstdint>
#include <cstring>
#include <vector>

// ============================================================================
// Big-endian read/write helpers
// ============================================================================
static inline void write_be16(uint8_t* buf, uint16_t v) {
    buf[0] = (uint8_t)((v >> 8) & 0xFF);
    buf[1] = (uint8_t)(v & 0xFF);
}

static inline uint16_t read_be16(const uint8_t* buf) {
    return ((uint16_t)buf[0] << 8) | (uint16_t)buf[1];
}

static inline void write_be32(uint8_t* buf, uint32_t v) {
    buf[0] = (uint8_t)((v >> 24) & 0xFF);
    buf[1] = (uint8_t)((v >> 16) & 0xFF);
    buf[2] = (uint8_t)((v >> 8) & 0xFF);
    buf[3] = (uint8_t)(v & 0xFF);
}

static inline uint32_t read_be32(const uint8_t* buf) {
    return ((uint32_t)buf[0] << 24) | ((uint32_t)buf[1] << 16)
         | ((uint32_t)buf[2] << 8)  |  (uint32_t)buf[3];
}

static inline void write_be64(uint8_t* buf, uint64_t v) {
    for (int i = 7; i >= 0; i--) {
        buf[7 - i] = (uint8_t)((v >> (i * 8)) & 0xFF);
    }
}

static inline uint64_t read_be64(const uint8_t* buf) {
    uint64_t v = 0;
    for (int i = 0; i < 8; i++) {
        v = (v << 8) | buf[i];
    }
    return v;
}

static inline void write_double(uint8_t* buf, double v) {
    uint64_t u;
    memcpy(&u, &v, sizeof(u));
    write_be64(buf, u);
}

static inline double read_double(const uint8_t* buf) {
    uint64_t u = read_be64(buf);
    double v;
    memcpy(&v, &u, sizeof(v));
    return v;
}

static inline void write_float_be(uint8_t* buf, float v) {
    uint32_t u;
    memcpy(&u, &v, sizeof(u));
    write_be32(buf, u);
}

static inline float read_float_be(const uint8_t* buf) {
    uint32_t u = read_be32(buf);
    float v;
    memcpy(&v, &u, sizeof(v));
    return v;
}

// ============================================================================
// Build HELLO body (v2 — registry model):
//   [2B]clientIdLen [NB]clientId
//   [2B]providesCount [N*4B]provides
//   [2B]subscribesCount [N*4B]subscribes
// ============================================================================
static inline std::vector<uint8_t> build_hello(const char* clientId,
                                               const int32_t* provides, uint16_t providesCount,
                                               const int32_t* subscribes, uint16_t subscribesCount) {
    size_t nameLen = strlen(clientId);
    std::vector<uint8_t> buf(2 + nameLen + 2 + (size_t)providesCount * 4 + 2 + (size_t)subscribesCount * 4);
    size_t off = 0;
    write_be16(buf.data() + off, (uint16_t)nameLen); off += 2;
    memcpy(buf.data() + off, clientId, nameLen);      off += nameLen;
    write_be16(buf.data() + off, providesCount);      off += 2;
    for (uint16_t i = 0; i < providesCount; i++) {
        write_be32(buf.data() + off, (uint32_t)provides[i]);
        off += 4;
    }
    write_be16(buf.data() + off, subscribesCount);    off += 2;
    for (uint16_t i = 0; i < subscribesCount; i++) {
        write_be32(buf.data() + off, (uint32_t)subscribes[i]);
        off += 4;
    }
    return buf;
}

// ============================================================================
// Socket helpers (blocking, handle partial send/recv)
// ============================================================================
static inline bool send_all(SOCKET sock, const void* data, size_t len) {
    const char* p = (const char*)data;
    while (len > 0) {
        int sent = send(sock, p, (int)len, 0);
        if (sent <= 0) return false;
        p += sent;
        len -= sent;
    }
    return true;
}

static inline bool recv_all(SOCKET sock, void* buf, size_t len) {
    char* p = (char*)buf;
    while (len > 0) {
        int rcvd = recv(sock, p, (int)len, 0);
        if (rcvd <= 0) return false;
        p += rcvd;
        len -= rcvd;
    }
    return true;
}

// Send a message: [4B msgType BE] [4B bodyLen BE] [body]
static inline bool send_message(SOCKET sock, int32_t type, const void* body, uint32_t body_len) {
    uint8_t header[8];
    write_be32(header, (uint32_t)type);
    write_be32(header + 4, body_len);
    if (!send_all(sock, header, 8)) return false;
    if (body_len > 0 && !send_all(sock, body, body_len)) return false;
    return true;
}

// Receive a message, returns msgType (or -1 on error), body stored in out param
static inline int32_t recv_message(SOCKET sock, std::vector<uint8_t>& body) {
    uint8_t header[8];
    if (!recv_all(sock, header, 8)) return -1;
    int32_t type = (int32_t)read_be32(header);
    uint32_t len = read_be32(header + 4);
    body.resize(len);
    if (len > 0 && !recv_all(sock, body.data(), len)) return -1;
    return type;
}

// ============================================================================
// Common HELLO message type (shared across all subprocesses)
// ============================================================================
enum CommonMsgType : int32_t {
    HELLO = 1,  // C++ -> Java (handshake: body = clientType string)
};

#endif // SOCKET_COMMON_H
