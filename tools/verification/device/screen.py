"""Minimal PNG inspection used to reject black/empty launch evidence."""

from __future__ import annotations

import struct
import zlib
from pathlib import Path
from typing import Iterable


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def inspect_png(path: Path) -> dict[str, object]:
    try:
        data = path.read_bytes()
        width, height, bit_depth, color_type, interlace, palette = _read_png(data)
        if bit_depth != 8 or interlace != 0:
            return {
                "valid_png": False,
                "non_black": False,
                "error": f"unsupported bit_depth={bit_depth} interlace={interlace}",
            }
        channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(color_type)
        if channels is None:
            return {"valid_png": False, "non_black": False, "error": f"color_type={color_type}"}
        compressed = _chunks(data, b"IDAT")
        raw = zlib.decompress(compressed)
        row_size = width * channels
        expected_size = (row_size + 1) * height
        if len(raw) != expected_size:
            return {
                "valid_png": False,
                "non_black": False,
                "error": f"decoded_size={len(raw)} expected={expected_size}",
            }
        rows: list[bytes] = []
        previous = bytes(row_size)
        offset = 0
        for _ in range(height):
            filter_type = raw[offset]
            offset += 1
            current = bytearray(raw[offset:offset + row_size])
            offset += row_size
            _unfilter(current, previous, filter_type, channels)
            row = bytes(current)
            rows.append(row)
            previous = row

        top = max(0, int(height * 0.08))
        bottom = min(height, max(top + 1, int(height * 0.92)))
        visible = 0
        total = 0
        brightness = 0
        unique: set[tuple[int, int, int]] = set()
        for row in rows[top:bottom]:
            for pixel in _pixels(row, width, color_type, palette):
                r, g, b, alpha = pixel
                total += 1
                if alpha > 5 and (r > 12 or g > 12 or b > 12):
                    visible += 1
                brightness += (r + g + b) // 3
                if len(unique) < 64:
                    unique.add((r, g, b))
        mean = brightness / total if total else 0.0
        # Exclude status/navigation bars and require a meaningful content area.
        non_black = total > 0 and visible >= max(64, int(total * 0.005)) and mean > 3.0
        return {
            "valid_png": True,
            "width": width,
            "height": height,
            "visible_pixels": visible,
            "sampled_pixels": total,
            "mean_luma": round(mean, 2),
            "distinct_colors_sampled": len(unique),
            "non_black": non_black,
        }
    except (OSError, ValueError, struct.error, zlib.error) as exc:
        return {"valid_png": False, "non_black": False, "error": str(exc)}


def _read_png(data: bytes) -> tuple[int, int, int, int, int, bytes]:
    if not data.startswith(PNG_SIGNATURE):
        raise ValueError("invalid PNG signature")
    ihdr = _first_chunk(data, b"IHDR")
    if len(ihdr) != 13:
        raise ValueError("invalid IHDR")
    width, height, bit_depth, color_type, _compression, _filter, interlace = struct.unpack(
        ">IIBBBBB", ihdr
    )
    if width <= 0 or height <= 0:
        raise ValueError("invalid PNG dimensions")
    palette = _optional_chunk(data, b"PLTE")
    return width, height, bit_depth, color_type, interlace, palette


def _chunks(data: bytes, kind: bytes) -> bytes:
    output = bytearray()
    offset = len(PNG_SIGNATURE)
    while offset + 12 <= len(data):
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        name = data[offset + 4:offset + 8]
        body = data[offset + 8:offset + 8 + length]
        if name == kind:
            output.extend(body)
        offset += 12 + length
        if name == b"IEND":
            break
    if not output:
        raise ValueError(f"PNG chunk {kind!r} missing")
    return bytes(output)


def _first_chunk(data: bytes, kind: bytes) -> bytes:
    return _chunks(data, kind)


def _optional_chunk(data: bytes, kind: bytes) -> bytes:
    try:
        return _chunks(data, kind)
    except ValueError:
        return b""


def _unfilter(current: bytearray, previous: bytes, filter_type: int, bpp: int) -> None:
    if filter_type == 0:
        return
    for index in range(len(current)):
        left = current[index - bpp] if index >= bpp else 0
        up = previous[index]
        upper_left = previous[index - bpp] if index >= bpp else 0
        if filter_type == 1:
            current[index] = (current[index] + left) & 0xFF
        elif filter_type == 2:
            current[index] = (current[index] + up) & 0xFF
        elif filter_type == 3:
            current[index] = (current[index] + ((left + up) // 2)) & 0xFF
        elif filter_type == 4:
            current[index] = (current[index] + _paeth(left, up, upper_left)) & 0xFF
        else:
            raise ValueError(f"unsupported PNG filter {filter_type}")


def _paeth(left: int, up: int, upper_left: int) -> int:
    estimate = left + up - upper_left
    left_distance = abs(estimate - left)
    up_distance = abs(estimate - up)
    upper_left_distance = abs(estimate - upper_left)
    if left_distance <= up_distance and left_distance <= upper_left_distance:
        return left
    if up_distance <= upper_left_distance:
        return up
    return upper_left


def _pixels(
    row: bytes, width: int, color_type: int, palette: bytes
) -> Iterable[tuple[int, int, int, int]]:
    if color_type == 0:
        for index in range(width):
            value = row[index]
            yield value, value, value, 255
    elif color_type == 2:
        for index in range(width):
            start = index * 3
            yield row[start], row[start + 1], row[start + 2], 255
    elif color_type == 3:
        if len(palette) % 3:
            raise ValueError("invalid PNG palette")
        for index in range(width):
            palette_index = row[index] * 3
            if palette_index + 2 >= len(palette):
                raise ValueError("PNG palette index out of bounds")
            yield (
                palette[palette_index],
                palette[palette_index + 1],
                palette[palette_index + 2],
                255,
            )
    elif color_type == 4:
        for index in range(width):
            start = index * 2
            value, alpha = row[start], row[start + 1]
            yield value, value, value, alpha
    elif color_type == 6:
        for index in range(width):
            start = index * 4
            yield row[start], row[start + 1], row[start + 2], row[start + 3]
