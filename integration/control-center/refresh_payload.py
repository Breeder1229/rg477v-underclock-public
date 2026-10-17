"""Refresh only owned helper DEX in the exact verified official v0.2 Control Center.

No APK decoding, vendor-code rewriting, network, device access, or key copying.
External Java/SDK/signing inputs are required. Outputs require a fresh directory.
"""
import argparse
import copy
from dataclasses import dataclass
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import zipfile

BASE_SHA256 = "43b345149548be671b963ff017487a5e8975f2b373a3abb17a5cc6aaa2ce0961"
BASE_BYTES = 3237713
CERT_SHA256 = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"
VENDOR_SHA256 = "f0c41feea335f900ed3d31d7ba28c43a835deede7a82c3fd08acbc07e3235d69"
OLD_HELPER_SHA256 = "3455579bcfd3c71a6220105a07ca9dd45a536aed63cc6983e532c502a1a38853"
NAMESPACE = "Lcom/anbernic/control/underclock/"
RUNTIME = NAMESPACE + "UnderclockRuntime;"
ENTRYPOINTS = (("boot", "(Landroid/content/Context;)V"), ("install", "(Landroid/view/View;)V"))
# Reviewed v0.3.0/v0.4.1 Custom inventory, including D8 lambda classes. Opt-in
# permits this exact feature set, never arbitrary additions to the owned namespace.
CUSTOM_HELPER_CLASSES = frozenset(NAMESPACE + name + ";" for name in (
    "MeasuredProfiles", "UnderclockCard", "UnderclockCard$Controller", "UnderclockCard$Result",
    "UnderclockCard$$ExternalSyntheticLambda0", "UnderclockCard$$ExternalSyntheticLambda1",
    "UnderclockCard$$ExternalSyntheticLambda2", "UnderclockCard$$ExternalSyntheticLambda3",
    "UnderclockCard$Attachment", "UnderclockCard$Attachment$1",
    "UnderclockCard$Attachment$$ExternalSyntheticLambda0", "UnderclockCard$Attachment$$ExternalSyntheticLambda1",
    "UnderclockCard$Attachment$$ExternalSyntheticLambda2", "UnderclockCard$Attachment$$ExternalSyntheticLambda3",
    "UnderclockCard$Attachment$$ExternalSyntheticLambda4", "UnderclockCard$Attachment$$ExternalSyntheticLambda5",
    "UnderclockCard$Attachment$$ExternalSyntheticLambda6", "UnderclockCard$Attachment$$ExternalSyntheticLambda7",
    "UnderclockRuntime", "UnderclockRuntime$Lease",
    "UnderclockRuntime$$ExternalSyntheticLambda0", "UnderclockRuntime$$ExternalSyntheticLambda1",
    "UnderclockRuntime$$ExternalSyntheticLambda2", "UnderclockRuntime$$ExternalSyntheticLambda3",
    "UnderclockRuntime$$ExternalSyntheticLambda4",
    "UnderclockRuntime$1", "UnderclockRuntime$2", "UnderclockRuntime$3", "UnderclockRuntime$4",
    "UnderclockRuntime$5", "UnderclockRuntime$6", "UnderclockRuntime$6$$ExternalSyntheticLambda0", "UnderclockRuntime$7",
    "shared/CustomProfileCommand", "shared/CustomProfileValues", "shared/CustomProfileStore",
    "shared/CustomProfileStore$Persistence", "shared/CustomProfileStore$Snapshot",
    "shared/CustomProfileEditor", "shared/CustomProfileEditor$1", "shared/CustomProfileEditor$Apply",
    "shared/CustomProfileEditor$Completion", "shared/CustomProfileEditor$$ExternalSyntheticLambda0",
    "shared/CustomProfileEditor$$ExternalSyntheticLambda1", "shared/CustomProfileEditor$$ExternalSyntheticLambda2",
    "shared/CustomProfileEditor$$ExternalSyntheticLambda3", "shared/CustomProfileEditor$$ExternalSyntheticLambda4",
))
BOOT_HELPER_CLASSES = CUSTOM_HELPER_CLASSES | frozenset((
    NAMESPACE + "BootRestorePolicy;", NAMESPACE + "BootRestorePolicy$Actions;",
))
POWER_OVERLAY_HELPER_CLASSES = BOOT_HELPER_CLASSES | frozenset(NAMESPACE + name + ";" for name in (
    "BatteryPowerEstimate",
    "PowerOverlay",
    "PowerOverlay$1",
    "PowerOverlay$2",
    "PowerOverlay$3",
    "PowerOverlay$$ExternalSyntheticLambda0",
    "PowerOverlay$$ExternalSyntheticLambda1",
    "PowerOverlay$$ExternalSyntheticLambda2",
    "PowerOverlay$$ExternalSyntheticLambda3",
    "PowerOverlay$1$$ExternalSyntheticLambda0",
    "PowerOverlay$1$$ExternalSyntheticLambda1",
    "PowerOverlayLifecycle",
    "PowerOverlayLifecycle$Host",
    "shared/PowerOverlayProtocol",
    "ForegroundFrameSampler",
    "PresentedFrameRate",
    "OsdReading",
))
EXPECTED_HOOKS = (
    ("Lu0/A;", "<init>", "(Landroid/content/Context;)V", 1332, "invoke-static",
     "v11, " + RUNTIME + "->install(Landroid/view/View;)V"),
    ("Lcom/anbernic/control/service/ControlCenterService;", "onCreate", "()V", 10, "invoke-static",
     "v5, " + RUNTIME + "->boot(Landroid/content/Context;)V"),
)
DEX_NAME = re.compile(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex\Z")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def verify_base_pin(data):
    if len(data) != BASE_BYTES or digest(data) != BASE_SHA256:
        raise ValueError("Base APK must be the exact verified official v0.2 installed Control Center")


def signature_entry(name):
    return re.fullmatch(r"META-INF/(?:MANIFEST\.MF|[A-Z0-9_-]+\.(?:SF|RSA|DSA|EC))", name.upper()) is not None


def validate_entry_name(name):
    if not name or name.startswith("/") or "\\" in name or ":" in name or any(ord(c) < 32 for c in name):
        raise ValueError("Unsafe ZIP entry name")
    parts = name.rstrip("/").split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise ValueError("Unsafe ZIP entry path")
    if re.fullmatch(r"classes[0-9]*\.dex", name, re.IGNORECASE) and not DEX_NAME.fullmatch(name):
        raise ValueError("Ambiguous root DEX entry name")


@dataclass
class Archive:
    infos: tuple
    entries: dict


def read_archive(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        infos = tuple(archive.infolist())
        if len(infos) > 20000 or sum(info.file_size for info in infos) > 512 * 1024 * 1024:
            raise ValueError("Unexpected APK archive size")
        names = [info.filename for info in infos]
        if len(set(names)) != len(names):
            raise ValueError("Duplicate exact ZIP entry names")
        # The pinned APK has legitimate obfuscated res/2F.xml vs res/2f.xml
        # pairs. We never extract ZIP paths to disk: preserve their exact names.
        # Case aliases outside res/, including DEX/signatures/manifest, stay blocked.
        folded = {}
        for name in names:
            prior = folded.get(name.casefold())
            if prior is not None and not (prior.startswith("res/") and name.startswith("res/")):
                raise ValueError("Case-ambiguous sensitive ZIP entry names")
            folded[name.casefold()] = name
        for info in infos:
            validate_entry_name(info.filename)
            if info.flag_bits & 1 or stat.S_ISLNK(info.external_attr >> 16):
                raise ValueError("Encrypted or symlink ZIP entry is not permitted")
            if info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                raise ValueError("Unsupported APK entry compression")
        entries = {info.filename: archive.read(info) for info in infos}
    dex_names = sorted((name for name in entries if DEX_NAME.fullmatch(name)), key=dex_number)
    if [dex_number(name) for name in dex_names] != list(range(1, len(dex_names) + 1)):
        raise ValueError("Root DEX entries must form a contiguous canonical sequence")
    if not dex_names:
        raise ValueError("APK has no root DEX")
    return Archive(infos, entries)


def dex_number(name):
    return 1 if name == "classes.dex" else int(name[7:-4])


@dataclass(frozen=True)
class DexInventory:
    classes: tuple
    hooks: tuple = ()
    runtime_methods: tuple = ()  # name, descriptor, flags, executable body present
    runtime_class_flags: int = 0


def parse_dex(data):
    from androguard.core.dex import DEX
    if not re.match(rb"dex\n0(?:3[5-9]|40)\x00", data[:8]):
        raise ValueError("Unsupported or invalid DEX magic")
    dex = DEX(data)
    classes, hooks, methods, runtime_flags = [], [], [], 0
    for cls in dex.get_classes():
        owner = cls.get_name()
        classes.append(owner)
        if owner == RUNTIME:
            runtime_flags = cls.get_access_flags()
        for method in cls.get_methods():
            name, descriptor = method.get_name(), method.get_descriptor()
            if owner == RUNTIME:
                methods.append((name, descriptor, method.get_access_flags(), method.get_code() is not None))
            if method.get_code() is None:
                continue
            offset = 0
            for instruction in method.get_instructions():
                opcode, output = instruction.get_name(), instruction.get_output()
                if opcode.startswith("invoke-") and NAMESPACE in output:
                    hooks.append((owner, name, descriptor, offset, opcode, output))
                offset += instruction.get_length()
    return DexInventory(tuple(classes), tuple(hooks), tuple(methods), runtime_flags)


def partition_dex(inventories):
    vendor, helper, seen = {}, {}, set()
    for entry, inventory in inventories.items():
        names = inventory.classes
        if not names:
            raise ValueError("An empty DEX cannot establish ownership")
        if any(not re.fullmatch(r"L[^;\s\[\].]+;", name) for name in names):
            raise ValueError("Invalid class descriptor")
        if len(set(names)) != len(names) or seen.intersection(names):
            raise ValueError("Duplicate class descriptor across DEX files")
        seen.update(names)
        owned = [name.startswith(NAMESPACE) for name in names]
        if any(owned) and not all(owned):
            raise ValueError("Mixed vendor/helper DEX must never be replaced")
        (helper if all(owned) else vendor)[entry] = inventory
    return vendor, helper


def validate_helpers(inventories, vendor_classes):
    vendor, helper = partition_dex(inventories)
    if vendor or not helper:
        raise ValueError("Every new helper class must be exclusively in the approved namespace")
    names = {name for inventory in helper.values() for name in inventory.classes}
    if names.intersection(vendor_classes):
        raise ValueError("New helper collides with vendor classes")
    if RUNTIME not in names:
        raise ValueError("Required UnderclockRuntime helper is missing")
    runtime_flags = [value.runtime_class_flags for value in helper.values() if RUNTIME in value.classes]
    if len(runtime_flags) != 1 or not runtime_flags[0] & 1:
        raise ValueError("UnderclockRuntime class must be public for the preserved vendor hooks")
    methods = [method for inventory in helper.values() for method in inventory.runtime_methods]
    for name, descriptor in ENTRYPOINTS:
        matches = [method for method in methods if method[:2] == (name, descriptor)]
        if len(matches) != 1 or matches[0][2] & 0x9 != 0x9 or matches[0][2] & 0x6 or not matches[0][3]:
            raise ValueError("Runtime must define executable public static " + name + descriptor)
    return names


def verify_hooks(vendor):
    hooks = [hook for inventory in vendor.values() for hook in inventory.hooks]
    if len(hooks) != 2 or set(hooks) != set(EXPECTED_HOOKS):
        raise ValueError("Expected exactly the two unchanged official v0.2 hook callsites")


def replacement_plan(base, helpers, parser=parse_dex, pinned=True, custom_profiles=False, boot_restore=False, power_overlay=False):
    if sum(map(bool, (custom_profiles, boot_restore, power_overlay))) > 1:
        raise ValueError("Choose exactly one reviewed helper mode")
    inventories = {name: parser(data) for name, data in base.entries.items() if DEX_NAME.fullmatch(name)}
    vendor, old_helpers = partition_dex(inventories)
    if not vendor or not old_helpers:
        raise ValueError("Both preserved vendor DEX and an owned helper DEX are required")
    if pinned:
        if set(vendor) != {"classes.dex"} or set(old_helpers) != {"classes2.dex"}:
            raise ValueError("Verified v0.2 DEX partition changed")
        if digest(base.entries["classes.dex"]) != VENDOR_SHA256 or digest(base.entries["classes2.dex"]) != OLD_HELPER_SHA256:
            raise ValueError("Verified v0.2 DEX bytes changed")
        if len(vendor["classes.dex"].classes) != 4211 or len(old_helpers["classes2.dex"].classes) != 23:
            raise ValueError("Verified v0.2 class inventory changed")
    verify_hooks(vendor)
    if len(helpers) != len(old_helpers):
        raise ValueError("New helper DEX count must match the owned DEX slots in this exact base")
    slots = sorted(old_helpers, key=dex_number)
    replacements = dict(zip(slots, helpers))
    fresh = {name: parser(data) for name, data in replacements.items()}
    vendor_classes = {name for inventory in vendor.values() for name in inventory.classes}
    new_classes = validate_helpers(fresh, vendor_classes)
    approved_classes = (POWER_OVERLAY_HELPER_CLASSES if power_overlay else BOOT_HELPER_CLASSES if boot_restore else CUSTOM_HELPER_CLASSES if custom_profiles else
                        {name for inventory in old_helpers.values() for name in inventory.classes})
    if new_classes != approved_classes:
        raise ValueError("Approved helper class set changed: missing or added owned classes")
    return replacements, vendor, fresh


def verify_preserved(base, candidate, replacements):
    expected = {name for name in base.entries if not signature_entry(name) and name not in replacements}
    actual = {name for name in candidate.entries if not signature_entry(name) and name not in replacements}
    if expected != actual:
        raise ValueError("Protected APK entries were added or removed")
    for name in expected:
        if base.entries[name] != candidate.entries[name]:
            raise ValueError("Protected APK entry bytes changed: " + name)
    for name, data in replacements.items():
        if candidate.entries.get(name) != data:
            raise ValueError("New helper DEX bytes changed or missing: " + name)
    return {name: digest(base.entries[name]) for name in sorted(expected)}


def compose(base, replacements, destination):
    with zipfile.ZipFile(destination, "x") as output:
        for info in base.infos:
            if not signature_entry(info.filename):
                output.writestr(copy.copy(info), replacements.get(info.filename, base.entries[info.filename]))


def publish_verified(staged, final, expected_sha256):
    if digest(staged.read_bytes()) != expected_sha256:
        raise ValueError("Staged artifact changed before publication")
    # Link creation is one atomic filesystem operation, with no partial-copy
    # state and no overwrite. Unsupported filesystems fail without publication.
    os.link(staged, final)
    try:
        if digest(final.read_bytes()) != expected_sha256:
            raise ValueError("Published artifact differs from the verified stage")
    except Exception:
        final.unlink(missing_ok=True)
        raise


def no_links(path):
    for part in (path, *path.parents):
        if part.is_symlink():
            raise ValueError("Symlink input/output paths are not permitted")
        if part.exists() and getattr(part.lstat(), "st_file_attributes", 0) & 0x400:
            raise ValueError("Reparse input/output paths are not permitted")


def input_path(value, directory=False):
    path = Path(value).expanduser()
    if ".." in path.parts:
        raise ValueError("Input path traversal is not permitted")
    no_links(path.absolute())
    path = path.resolve(strict=True)
    if not (path.is_dir() if directory else path.is_file()):
        raise ValueError("Explicit existing input file/directory required")
    return path


def prepare_work_dir(value, protected):
    raw = Path(value).expanduser()
    if not raw.is_absolute() or ".." in raw.parts:
        raise ValueError("Work directory must be an absolute new path without traversal")
    no_links(raw)
    path = raw.resolve()
    if path.exists() or path.is_symlink():
        raise ValueError("Work directory must not already exist, even if empty")
    if not path.parent.is_dir():
        raise ValueError("Work directory parent must already exist")
    for protected_path in protected:
        target = Path(protected_path).resolve(strict=True)
        if path == target or path.is_relative_to(target) or target.is_relative_to(path):
            raise ValueError("Work/output path overlaps a protected input")
    path.mkdir(mode=0o700)
    return path


def distinct_files(paths):
    for index, left in enumerate(paths):
        for right in paths[index+1:]:
            if left == right or os.path.samefile(left, right):
                raise ValueError("Input/tool/key/certificate paths must not alias each other")


def tool_run(command, signing=False):
    result = subprocess.run([str(item) for item in command], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", check=False)
    if result.returncode:
        # In particular, never echo a signing tool's diagnostics or secret input.
        raise ValueError("Signing command failed; secret diagnostics withheld" if signing else "External verification/alignment command failed")
    return result.stdout + result.stderr


def verify_signature_log(text, required_schemes=(2, 3)):
    signers = re.findall(r"^Signer #(\d+) certificate SHA-256 digest:\s*([a-fA-F0-9:]+)\s*$", text, re.MULTILINE)
    if len(signers) != 1 or signers[0][0] != "1" or signers[0][1].replace(":", "").lower() != CERT_SHA256:
        raise ValueError("apksigner certificate pin or signer count mismatch")
    for scheme in required_schemes:
        if not re.search(r"^Verified using v" + str(scheme) + r" scheme[^\r\n]*:\s*true\s*$", text, re.MULTILINE):
            raise ValueError("apksigner did not verify the required signing scheme")


def verify_apk_identity(path):
    from androguard.core.apk import APK
    apk = APK(str(path))
    manifest = apk.get_android_manifest_xml()
    if (apk.get_package(), apk.get_androidversion_code(), apk.get_androidversion_name()) != ("com.anbernic.control", "9", "1.0.29"):
        raise ValueError("Package/version identity is not the verified official v0.2 Control Center")
    if manifest is None or manifest.get("{http://schemas.android.com/apk/res/android}sharedUserId") != "android.uid.system":
        raise ValueError("Expected android.uid.system shared UID")
    certificates = {digest(certificate.dump()) for certificate in apk.get_certificates()}
    if certificates != {CERT_SHA256}:
        raise ValueError("APK certificate pin mismatch")


def verify_signing_material(key, certificate):
    from cryptography import x509
    from cryptography.hazmat.primitives import serialization
    try:
        public_data = certificate.read_bytes()
        if public_data.count(b"-----BEGIN CERTIFICATE-----") != 1 or public_data.count(b"-----END CERTIFICATE-----") != 1:
            raise ValueError()
        cert = x509.load_pem_x509_certificate(public_data)
        if digest(cert.public_bytes(serialization.Encoding.DER)) != CERT_SHA256:
            raise ValueError()
        if key.stat().st_size > 65536:
            raise ValueError()
        private = serialization.load_der_private_key(key.read_bytes(), password=None)
        encoding, kind = serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
        if private.public_key().public_bytes(encoding, kind) != cert.public_key().public_bytes(encoding, kind):
            raise ValueError()
    except Exception:
        raise ValueError("External signing material does not match the pinned public AOSP certificate; no key data logged") from None


def execute(args):
    base_path = input_path(args.base_apk)
    helper_paths = [input_path(path) for path in args.helper_dex]
    java, tools = input_path(args.java), input_path(args.build_tools, directory=True)
    key, certificate = input_path(args.signing_key), input_path(args.signing_cert)
    zipalign = input_path(tools / ("zipalign.exe" if (tools / "zipalign.exe").is_file() else "zipalign"))
    signer = input_path(tools / "lib/apksigner.jar")
    protected = [base_path, *helper_paths, java, tools, key, certificate, Path(__file__).resolve()]
    distinct_files([base_path, *helper_paths, java, zipalign, signer, key, certificate])
    if args.python_deps:
        dependencies = input_path(args.python_deps, directory=True)
        protected.append(dependencies)
        sys.path.insert(0, str(dependencies))
    from loguru import logger
    logger.remove()
    import androguard
    if getattr(androguard, "__version__", None) != "4.1.3":
        raise ValueError("The reviewed androguard 4.1.3 dependency tree is required")
    source = base_path.read_bytes()
    verify_base_pin(source)
    helper_data = [path.read_bytes() for path in helper_paths]
    base = read_archive(source)
    boot_restore = getattr(args, "boot_restore", False)
    power_overlay = getattr(args, "power_overlay", False)
    replacements, vendor, fresh = replacement_plan(base, helper_data, custom_profiles=args.custom_profiles, boot_restore=boot_restore, power_overlay=power_overlay)
    verify_apk_identity(base_path)
    verify_signing_material(key, certificate)
    signer_command = [java, "-jar", signer]
    base_signature = tool_run(signer_command + ["verify", "--verbose", "--print-certs", base_path])
    verify_signature_log(base_signature)
    # Only after all read-only input checks may a fresh private output folder exist.
    work = prepare_work_dir(args.work_dir, protected)
    unsigned, aligned, staged = (work / name for name in ("composed.apk", "aligned.apk", "staged-signed.apk"))
    final = work / "control-center-underclock-signed.apk"
    try:
        compose(base, replacements, unsigned)
        verify_preserved(base, read_archive(unsigned.read_bytes()), replacements)
        tool_run([zipalign, "-P", "16", "4", unsigned, aligned])
        verify_preserved(base, read_archive(aligned.read_bytes()), replacements)
        if base_path.read_bytes() != source or any(path.read_bytes() != data for path, data in zip(helper_paths, helper_data)):
            raise ValueError("Base/helper inputs changed during refresh")
        verify_signing_material(key, certificate)
        tool_run(signer_command + ["sign", "--key", key, "--cert", certificate,
                 "--v1-signing-enabled", "true", "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
                 "--out", staged, aligned], signing=True)
        signature = tool_run(signer_command + ["verify", "--verbose", "--print-certs", staged])
        verify_signature_log(signature)
        v1_signature = tool_run(signer_command + ["verify", "--verbose", "--print-certs",
                                "--min-sdk-version", "23", "--max-sdk-version", "23", staged])
        verify_signature_log(v1_signature, (1,))
        tool_run([zipalign, "-c", "-P", "16", "4", staged])
        signed_data = staged.read_bytes()
        signed_archive = read_archive(signed_data)
        preserved = verify_preserved(base, signed_archive, replacements)
        verify_apk_identity(staged)
        final_inventories = {name: parse_dex(data) for name, data in signed_archive.entries.items() if DEX_NAME.fullmatch(name)}
        final_vendor, final_helper = partition_dex(final_inventories)
        verify_hooks(final_vendor)
        final_classes = validate_helpers(final_helper, {name for inventory in final_vendor.values() for name in inventory.classes})
        approved_classes = {name for inventory in fresh.values() for name in inventory.classes}
        if final_classes != approved_classes:
            raise ValueError("Signed helper class set differs from the reviewed replacement")
        evidence = {"mode": "verified-v02-helper-only-refresh", "base_sha256": BASE_SHA256, "base_bytes": BASE_BYTES,
                    "package": "com.anbernic.control", "version_name": "1.0.29", "version_code": 9,
                    "shared_uid": "android.uid.system", "certificate_sha256": CERT_SHA256,
                    "verified_schemes": [1, 2, 3], "zipalign_native_page_kib": 16,
                    "helper_namespace": NAMESPACE, "custom_profiles": args.custom_profiles or boot_restore or power_overlay,
                    "boot_restore": boot_restore or power_overlay, "power_overlay": power_overlay, "vendor_dex_byte_identical": True,
                    "all_non_signature_non_helper_entry_payloads_byte_identical": True,
                    "protected_entry_count": len(preserved), "protected_entry_sha256": preserved,
                    "old_helper_dex_sha256": {name: digest(base.entries[name]) for name in replacements},
                    "new_helper_dex_sha256": {name: digest(data) for name, data in replacements.items()},
                    "helper_class_count": sum(len(inventory.classes) for inventory in fresh.values()),
                    "exact_approved_helper_class_set_preserved": True, "approved_helper_classes": sorted(approved_classes),
                    "existing_hook_callsites_unchanged": [list(hook) for hook in EXPECTED_HOOKS],
                    "runtime_public_static_entrypoints_verified": True,
                    "signed_apk_sha256": digest(signed_data), "signed_apk_bytes": len(signed_data),
                    "signed_apk_path": str(final)}
        # Same-directory hard-link publication is atomic and never overwrites an
        # existing destination. A failed disk write cannot leave a partial final APK.
        publish_verified(staged, final, evidence["signed_apk_sha256"])
        (work / "signature-verification.txt").write_text(signature + "\nExplicit v1 verification:\n" + v1_signature, encoding="utf-8")
        (work / "refresh-verification.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
        return evidence
    finally:
        for temporary in (unsigned, aligned, staged):
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-apk", type=Path, required=True, help="Exact official v0.2 installed Control Center APK, not a stock replacement")
    parser.add_argument("--helper-dex", type=Path, action="append", required=True, help="Reviewed owned helper DEX; count must match existing owned slots")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--custom-profiles", action="store_true", help="Require the original exact 47-class Custom inventory")
    modes.add_argument("--boot-restore", action="store_true", help="Require exactly 49 reviewed Custom/bootstrap classes")
    modes.add_argument("--power-overlay", action="store_true", help="Require exactly 66 reviewed Custom/bootstrap/power-FPS overlay classes")
    parser.add_argument("--java", type=Path, required=True, help="Explicit Java executable")
    parser.add_argument("--build-tools", type=Path, required=True, help="SDK build-tools containing zipalign and lib/apksigner.jar")
    parser.add_argument("--signing-key", type=Path, required=True, help="External matching public AOSP platform PKCS8 test key; never copied")
    parser.add_argument("--signing-cert", type=Path, required=True, help="External pinned platform.x509.pem")
    parser.add_argument("--python-deps", type=Path, help="Explicit existing androguard 4.1.3 dependency directory")
    parser.add_argument("--work-dir", type=Path, required=True, help="Absolute non-existing private output directory with existing parent")
    args = parser.parse_args()
    try:
        evidence = execute(args)
    except (ValueError, OSError, zipfile.BadZipFile) as failure:
        parser.exit(1, "Refresh rejected: " + str(failure) + "\n")
    print(json.dumps(evidence, indent=2))


if __name__ == "__main__":
    main()
