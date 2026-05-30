from __future__ import annotations

import unittest

from Crypto.Cipher import AES

import Data
import rkpp_analysis as analysis
import rkpp_proto as proto
from rkpp_network import FIXED_AES_IV, decrypt_4013_body_candidates


def _tsf4g_trailer(input_len: int) -> bytes:
    rem = input_len % 16
    trailer_len = 16 - rem if rem <= 10 else 32 - rem
    return (b"\x00" * (trailer_len - 6)) + b"tsf4g" + bytes([trailer_len])


def _internal_plain(*, direction: str, opcode: int, payload: bytes) -> bytes:
    session_id = (0x12340000 | opcode) if direction == "s2c" else 0x12345678
    sub_id = 0 if direction == "s2c" else opcode
    header = (
        (1).to_bytes(4, "big")
        + b"\x55\xaa\x00\x00"
        + (26 + len(payload)).to_bytes(2, "big")
        + b"\x00\x00"
        + b"\x00\x00\x00\x01"
        + session_id.to_bytes(4, "big")
        + b"\x00\x01"
        + sub_id.to_bytes(2, "big")
        + b"\x39\x63\x00\x00"
        + (0x1234).to_bytes(2, "big")
    )
    raw = header + payload
    return raw + _tsf4g_trailer(len(raw))


class NewVersionAdaptationTests(unittest.TestCase):
    def test_generated_data_contains_new_opcode_and_lookup_names(self) -> None:
        self.assertEqual(analysis.lookup_opcode(0x028D)["name"], "ZoneGetHandbookSeasonAwardReq")

        maps = Data.get_maps()
        self.assertEqual(maps["attr"][1], "生命")
        self.assertEqual(maps["pet"][3001], "喵喵")
        self.assertEqual(maps["skill"][200001], "光之精灵")

    def test_flattened_payload_uses_opcode_payload_decoder(self) -> None:
        payload = bytes.fromhex(
            "08c6172a2210c88b04180028808df1cf06420710f403180220014a0710f4031802200150646000"
            "3804400248ece3fdf5bba694035000"
        )
        record = {
            "opcode": 0x0260,
            "opcode_hex": "0x0260",
            "root": proto.parse_proto_message(payload),
            "payload_hex": payload.hex(),
        }

        decoded = analysis.decode_record(record)

        self.assertIsNotNone(decoded)
        assert decoded is not None
        self.assertEqual(decoded["decode_source"], "flattened:shop_data")
        self.assertEqual(decoded["decoded"]["id"], 3014)
        self.assertEqual(decoded["decoded"]["goods_data"][0]["goods_id"], 67016)

    def test_fixed_iv_decrypt_candidate_parses_tgcp_internal_header(self) -> None:
        key = b"0123456789abcdef"
        payload = bytes.fromhex("08863f")
        plain = _internal_plain(direction="s2c", opcode=0x0260, payload=payload)
        cipher = AES.new(key, AES.MODE_CBC, FIXED_AES_IV).encrypt(plain)

        candidates = decrypt_4013_body_candidates(key, cipher)
        mode, _iv, _cipher, decrypted = candidates[0]
        record = proto.parse_record({
            "cmd": 0x4013,
            "direction": "s2c",
            "seq": 1,
            "decrypted_body_hex": decrypted.hex(),
        })

        self.assertEqual(mode, "fixed_iv")
        self.assertIsNotNone(record)
        assert record is not None
        self.assertEqual(record["transport_layout"], "tgcp_4013_v14")
        self.assertEqual(record["opcode"], 0x0260)
        self.assertEqual(record["payload_hex"], payload.hex())

    def test_c2s_7ca2_request_record_is_parsed_as_opcode_payload(self) -> None:
        payload = bytes.fromhex("12033f3f3f")
        body = (
            bytes.fromhex("6eb9a068")
            + (0x0149).to_bytes(4, "big")
            + bytes.fromhex("7ca2")
            + (42).to_bytes(4, "big")
            + payload
            + _tsf4g_trailer(14 + len(payload))
        )
        record = proto.parse_record({
            "cmd": 0x4013,
            "direction": "c2s",
            "seq": 77,
            "decrypted_body_hex": body.hex(),
        })

        self.assertIsNotNone(record)
        assert record is not None
        self.assertEqual(record["format"], "c2s_alt_7ca2")
        self.assertEqual(record["opcode"], 0x0149)
        self.assertEqual(record["opcode_hex"], "0x0149")
        self.assertEqual(record["transport_layout"], "tgcp_4013_live_c2s_alt_7ca2")
        self.assertEqual(record["prefix_u32_hex"], "0x6EB9A068")
        self.assertEqual(record["magic_hex"], "0x7CA2")
        self.assertEqual(record["req_seq"], 42)
        self.assertEqual(record["payload_hex"], payload.hex())


if __name__ == "__main__":
    unittest.main()
