"""Minimal JVM class-file reader.

We do not need bytecode, only:
  - the class name
  - referenced class names and UTF-8 strings (constant pool)
  - field access flags (static mutable state)
  - method access flags (native methods)
  - class-level annotations (Mixin targets and injection kinds)

Everything else is skipped by length.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set, Tuple

ACC_STATIC = 0x0008
ACC_FINAL = 0x0010
ACC_NATIVE = 0x0100

TAG_UTF8 = 1
TAG_INT = 3
TAG_FLOAT = 4
TAG_LONG = 5
TAG_DOUBLE = 6
TAG_CLASS = 7
TAG_STRING = 8
TAG_FIELDREF = 9
TAG_METHODREF = 10
TAG_INTERFACE_METHODREF = 11
TAG_NAMEANDTYPE = 12
TAG_METHODHANDLE = 15
TAG_METHODTYPE = 16
TAG_DYNAMIC = 17
TAG_INVOKEDYNAMIC = 18
TAG_MODULE = 19
TAG_PACKAGE = 20

MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;"


class ClassFileError(Exception):
    pass


@dataclass
class ParsedClass:
    name: str
    major: int
    class_refs: Set[str] = field(default_factory=set)
    strings: Set[str] = field(default_factory=set)
    annotations: Set[str] = field(default_factory=set)
    mixin_targets: Set[str] = field(default_factory=set)
    method_refs: Set[Tuple[str, str]] = field(default_factory=set)
    static_mutable_fields: int = 0
    native_methods: int = 0
    method_count: int = 0
    field_count: int = 0
    has_clinit: bool = False
    size: int = 0


class _Reader:
    __slots__ = ("data", "pos", "length")

    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0
        self.length = len(data)

    def u1(self) -> int:
        if self.pos + 1 > self.length:
            raise ClassFileError("truncated u1")
        value = self.data[self.pos]
        self.pos += 1
        return value

    def u2(self) -> int:
        if self.pos + 2 > self.length:
            raise ClassFileError("truncated u2")
        value = struct.unpack_from(">H", self.data, self.pos)[0]
        self.pos += 2
        return value

    def u4(self) -> int:
        if self.pos + 4 > self.length:
            raise ClassFileError("truncated u4")
        value = struct.unpack_from(">I", self.data, self.pos)[0]
        self.pos += 4
        return value

    def skip(self, count: int) -> None:
        if count < 0 or self.pos + count > self.length:
            raise ClassFileError("truncated skip")
        self.pos += count


def _desc_to_internal(desc: str) -> str:
    """Lnet/minecraft/client/Minecraft; -> net/minecraft/client/Minecraft"""
    if desc.startswith("L") and desc.endswith(";"):
        return desc[1:-1]
    if desc.startswith("[") and desc.endswith(";"):
        return _desc_to_internal(desc[1:])
    return desc


def _parse_element(reader: _Reader, utf8: List[Optional[str]]) -> Tuple[str, Any]:
    tag = chr(reader.u1())
    if tag == "c":
        idx = reader.u2()
        return ("class", _desc_to_internal(utf8[idx] or ""))
    if tag == "s":
        idx = reader.u2()
        return ("string", utf8[idx] or "")
    if tag == "[":
        count = reader.u2()
        return ("array", [_parse_element(reader, utf8) for _ in range(count)])
    if tag == "@":
        return ("annotation", _parse_annotation(reader, utf8))
    if tag == "e":
        type_idx = reader.u2()
        const_idx = reader.u2()
        return ("enum", (utf8[type_idx], utf8[const_idx]))
    # primitive constants (B C D F I J S Z) store a constant-pool index
    reader.u2()
    return ("const", tag)


def _parse_annotation(reader: _Reader, utf8: List[Optional[str]]) -> Tuple[str, Dict[str, Any]]:
    type_idx = reader.u2()
    desc = utf8[type_idx] or ""
    count = reader.u2()
    values: Dict[str, Any] = {}
    for _ in range(count):
        name_idx = reader.u2()
        name = utf8[name_idx] or ""
        values[name] = _parse_element(reader, utf8)
    return (desc, values)


def _flatten(value: Any) -> List[Tuple[str, Any]]:
    kind, payload = value
    if kind == "array":
        out: List[Tuple[str, Any]] = []
        for item in payload:
            out.extend(_flatten(item))
        return out
    return [value]


def _mixin_targets(desc: str, values: Dict[str, Any]) -> Set[str]:
    targets: Set[str] = set()
    if desc != MIXIN_DESC:
        return targets
    for key in ("value", "targets"):
        if key not in values:
            continue
        for kind, payload in _flatten(values[key]):
            if kind == "class" and payload:
                targets.add(str(payload))
            elif kind == "string" and payload:
                targets.add(str(payload).replace(".", "/"))
    return targets


def parse_class(data: bytes) -> ParsedClass:
    reader = _Reader(data)
    if reader.u4() != 0xCAFEBABE:
        raise ClassFileError("bad magic")
    reader.u2()  # minor
    major = reader.u2()

    count = reader.u2()
    utf8: List[Optional[str]] = [None] * count
    class_name_index: List[int] = [0] * count
    member_refs: List[Tuple[int, int]] = []
    name_and_types: Dict[int, Tuple[int, int]] = {}
    i = 1
    while i < count:
        tag = reader.u1()
        if tag == TAG_UTF8:
            length = reader.u2()
            if reader.pos + length > reader.length:
                raise ClassFileError("truncated utf8")
            raw = reader.data[reader.pos:reader.pos + length]
            reader.pos += length
            utf8[i] = raw.decode("utf-8", errors="replace")
        elif tag in (TAG_INT, TAG_FLOAT):
            reader.u4()
        elif tag in (TAG_LONG, TAG_DOUBLE):
            reader.skip(8)
            i += 1  # long/double take two constant-pool slots
        elif tag == TAG_CLASS:
            class_name_index[i] = reader.u2()
        elif tag == TAG_STRING:
            reader.u2()
        elif tag in (TAG_FIELDREF, TAG_METHODREF, TAG_INTERFACE_METHODREF):
            member_refs.append((reader.u2(), reader.u2()))
        elif tag == TAG_NAMEANDTYPE:
            name_and_types[i] = (reader.u2(), reader.u2())
        elif tag in (TAG_DYNAMIC, TAG_INVOKEDYNAMIC):
            reader.u2()
            reader.u2()
        elif tag == TAG_METHODHANDLE:
            reader.u1()
            reader.u2()
        elif tag in (TAG_METHODTYPE, TAG_MODULE, TAG_PACKAGE):
            reader.u2()
        else:
            raise ClassFileError("unknown constant pool tag %d at %d" % (tag, i))
        i += 1

    reader.u2()  # access_flags
    this_class = reader.u2()
    reader.u2()  # super_class
    interfaces = reader.u2()
    for _ in range(interfaces):
        reader.u2()

    class_refs: Set[str] = set()
    for idx, name_idx in enumerate(class_name_index):
        if idx == 0 or name_idx == 0:
            continue
        if 0 < name_idx < count and utf8[name_idx]:
            class_refs.add(utf8[name_idx])  # type: ignore[arg-type]

    strings: Set[str] = {value for value in utf8 if value}
    method_refs: Set[Tuple[str, str]] = set()
    for class_idx, nat_idx in member_refs:
        if not (0 < class_idx < count and 0 < nat_idx < count):
            continue
        cls_name_idx = class_name_index[class_idx]
        pair = name_and_types.get(nat_idx)
        if not (0 < cls_name_idx < count) or pair is None:
            continue
        cls_name = utf8[cls_name_idx]
        method_name = utf8[pair[0]]
        if cls_name and method_name:
            method_refs.add((cls_name, method_name))
    name = ""
    if 0 < this_class < count and class_name_index[this_class] and utf8[class_name_index[this_class]]:
        name = utf8[class_name_index[this_class]]  # type: ignore[assignment]

    fields = reader.u2()
    static_mutable = 0
    for _ in range(fields):
        access = reader.u2()
        reader.u2()  # name
        reader.u2()  # descriptor
        attrs = reader.u2()
        if (access & ACC_STATIC) and not (access & ACC_FINAL):
            static_mutable += 1
        for _ in range(attrs):
            reader.u2()
            length = reader.u4()
            reader.skip(length)

    methods = reader.u2()
    native_methods = 0
    has_clinit = False
    for _ in range(methods):
        access = reader.u2()
        name_idx = reader.u2()
        reader.u2()  # descriptor
        attrs = reader.u2()
        if access & ACC_NATIVE:
            native_methods += 1
        if 0 < name_idx < count and utf8[name_idx] == "<clinit>":
            has_clinit = True
        for _ in range(attrs):
            reader.u2()
            length = reader.u4()
            reader.skip(length)

    annotations: Set[str] = set()
    mixin_targets: Set[str] = set()
    attrs = reader.u2()
    for _ in range(attrs):
        name_idx = reader.u2()
        length = reader.u4()
        end = reader.pos + length
        if end > reader.length:
            raise ClassFileError("truncated class attribute")
        if 0 < name_idx < count and utf8[name_idx] in (
                "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"):
            try:
                num = reader.u2()
                for _ in range(num):
                    desc, values = _parse_annotation(reader, utf8)
                    annotations.add(desc)
                    if desc == MIXIN_DESC:
                        mixin_targets.update(_mixin_targets(desc, values))
            except (ClassFileError, IndexError, struct.error):
                pass
        reader.pos = end

    return ParsedClass(
        name=name,
        major=major,
        class_refs=class_refs,
        strings=strings,
        annotations=annotations,
        mixin_targets=mixin_targets,
        method_refs=method_refs,
        static_mutable_fields=static_mutable,
        native_methods=native_methods,
        method_count=methods,
        field_count=fields,
        has_clinit=has_clinit,
        size=len(data),
    )
