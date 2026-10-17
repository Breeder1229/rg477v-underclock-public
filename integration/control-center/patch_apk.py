"""Patch user-owned stock APK locally; never communicates with a device.

The APK's complete original manifest/resources/assets/native libraries are copied
byte-for-byte. Only stock DEX hooks and a supplied helper DEX are changed.
"""
import argparse, hashlib, json, re, shutil, subprocess, sys, tempfile, zipfile
from pathlib import Path

EXPECTED_SOURCE='37b7cb60e393176c2208d1b9cf1666d14b9df89e9b8367d28eb0f4bddf85877a'
APKTOOL_SHA256='dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423'
ROOT=SOURCE=JAVA=BUILD_TOOLS=APKTOOL=SIGNING_KEY=SIGNING_CERT=ZIPALIGN=None
DECODED=STOCK_REBUILT=COMPOSED=ALIGNED=SIGNED=None
RUNTIME='Lcom/anbernic/control/underclock/UnderclockRuntime;'
CERT_SHA256='c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8'

def sha(data): return hashlib.sha256(data).hexdigest()

def configure(args):
    global ROOT,SOURCE,JAVA,BUILD_TOOLS,APKTOOL,SIGNING_KEY,SIGNING_CERT,ZIPALIGN
    global DECODED,STOCK_REBUILT,COMPOSED,ALIGNED,SIGNED
    if args.python_deps:
        dependency_dir=args.python_deps.resolve(strict=True)
        if not dependency_dir.is_dir():raise ValueError('--python-deps must name a dependency directory')
        sys.path.insert(0,str(dependency_dir))
    SOURCE=args.stock_apk.resolve(strict=True)
    APKTOOL=args.apktool.resolve(strict=True)
    SIGNING_KEY=args.signing_key.resolve(strict=True)
    SIGNING_CERT=args.signing_cert.resolve(strict=True)
    BUILD_TOOLS=args.build_tools.resolve(strict=True)
    java_candidate=shutil.which(args.java)
    JAVA=Path(java_candidate or args.java).resolve(strict=True)
    for path in (SOURCE,APKTOOL,SIGNING_KEY,SIGNING_CERT,JAVA):
        if not path.is_file():raise ValueError('Expected an explicit input file: '+str(path))
    if sha(APKTOOL.read_bytes())!=APKTOOL_SHA256:
        raise ValueError('Only the verified official Apktool 3.0.3 fatjar is supported')
    ZIPALIGN=BUILD_TOOLS/('zipalign.exe' if (BUILD_TOOLS/'zipalign.exe').is_file() else 'zipalign')
    if not ZIPALIGN.is_file() or not (BUILD_TOOLS/'lib/apksigner.jar').is_file():
        raise ValueError('Android SDK build-tools zipalign and lib/apksigner.jar are required')
    ROOT=args.work_dir.resolve() if args.work_dir else Path(tempfile.mkdtemp(prefix='rg477v-control-center-'))
    ROOT.mkdir(parents=True,exist_ok=True)
    DECODED=ROOT/'decoded'
    STOCK_REBUILT=ROOT/'stock-hooks-rebuilt.apk'
    COMPOSED=ROOT/'control-center-underclock-unaligned.apk'
    ALIGNED=ROOT/'control-center-underclock-unsigned.apk'
    SIGNED=ROOT/'control-center-underclock-signed.apk'
    if SOURCE in (STOCK_REBUILT,COMPOSED,ALIGNED,SIGNED):
        raise ValueError('Input stock APK must not overlap a generated output path')
    for helper in args.helper_dex:
        if not helper.resolve(strict=True).is_file():raise ValueError('Helper DEX must be an existing file')

def run(args):
    result=subprocess.run([str(x) for x in args],check=True,capture_output=True,text=True,encoding='utf-8',errors='replace')
    if result.stdout.strip(): print(result.stdout.strip())
    if result.stderr.strip(): print(result.stderr.strip(),file=sys.stderr)
    return result.stdout+result.stderr

def method_region(text, declaration):
    start=text.index(declaration)
    end=text.index('.end method',start)+len('.end method')
    return start,end,text[start:end]

def patch_method(path, declaration, marker, before, after, evidence_name, expected_locals):
    text=path.read_text(encoding='utf-8')
    start,end,method=method_region(text,declaration)
    if f'.locals {expected_locals}' not in method: raise RuntimeError('Unexpected register allocation in '+declaration)
    if marker in method:
        if method.count(marker)!=1:raise RuntimeError('Duplicate hook in '+declaration)
        return
    if method.count(before)!=1:raise RuntimeError('Expected exactly one hook anchor in '+declaration)
    (ROOT/evidence_name).write_text(method+'\n',encoding='utf-8')
    patched=method.replace(before,after)
    path.write_text(text[:start]+patched+text[end:],encoding='utf-8',newline='\n')

def prepare():
    if sha(SOURCE.read_bytes())!=EXPECTED_SOURCE:raise RuntimeError('Input stock APK fingerprint mismatch')
    if not DECODED.exists():run([JAVA,'-jar',APKTOOL,'decode','-r',SOURCE,'-o',DECODED])
    with zipfile.ZipFile(SOURCE) as z:
        for name in ['AndroidManifest.xml','resources.arsc']:
            if (DECODED/name).read_bytes()!=z.read(name):raise RuntimeError('Decoded raw file changed: '+name)
    fragment=DECODED/'smali/com/anbernic/control/ui/controlcenter/tabs/PerformanceFragment.smali'
    service=DECODED/'smali/com/anbernic/control/service/ControlCenterService.smali'
    # The right-edge panel uses Lu0/A, not the legacy Fragment. Restore the
    # earlier unused prototype hook if this working tree contains it.
    text=fragment.read_text(encoding='utf-8')
    start,end,method=method_region(text,'.method public initView()V')
    if RUNTIME+'->install(' in method:
        original=(ROOT/'original-performance-initView.smali').read_text(encoding='utf-8').rstrip('\n')
        fragment.write_text(text[:start]+original+text[end:],encoding='utf-8',newline='\n')
    active_paths=[p for p in (DECODED/'smali').rglob('A.smali') if p.read_text(encoding='utf-8')[:300].startswith('.class public final Lu0/A;')]
    if len(active_paths)!=1:raise RuntimeError('Cannot uniquely locate active performance panel Lu0/A')
    active=active_paths[0]
    anchor='    return-void'
    # Lu0/A moves content out of binding.a's FrameLayout and into its own
    # ScrollView during construction. p0 is the actual live hierarchy root.
    ui_hook='    # Local Under-clock integration into the active right-edge performance panel.\n    invoke-static {p0}, '+RUNTIME+'->install(Landroid/view/View;)V\n\n'
    patch_method(active,'.method public constructor <init>(Landroid/content/Context;)V',RUNTIME+'->install(',anchor,ui_hook+anchor,'original-active-performance-constructor.smali',11)
    anchor='    invoke-super {p0}, Landroid/app/Service;->onCreate()V'
    boot_hook='\n\n    # Local keeper lifecycle hook; stock Service.onCreate and all original code retained.\n    invoke-static {p0}, '+RUNTIME+'->boot(Landroid/content/Context;)V'
    patch_method(service,'.method public onCreate()V',RUNTIME+'->boot(',anchor,anchor+boot_hook,'original-service-onCreate.smali',5)
    return {'stock_sha256':EXPECTED_SOURCE,'ui_hook':'Lu0/A constructor before return, using active ScrollView p0',
            'keeper_hook':'ControlCenterService.onCreate after Service.onCreate','register_counts_changed':False}

def signature_entry(name):
    upper=name.upper()
    return upper=='META-INF/MANIFEST.MF' or (upper.startswith('META-INF/') and upper.endswith(('.SF','.RSA','.DSA','.EC')))

def compose(helper_dex):
    with zipfile.ZipFile(SOURCE) as original, zipfile.ZipFile(STOCK_REBUILT) as rebuilt, zipfile.ZipFile(COMPOSED,'w') as target:
        original_dex=[x for x in original.namelist() if re.fullmatch(r'classes\d*\.dex',x)]
        rebuilt_dex=[x for x in rebuilt.namelist() if re.fullmatch(r'classes\d*\.dex',x)]
        if original_dex!=['classes.dex'] or set(rebuilt_dex)!=set(original_dex):raise RuntimeError('Unexpected stock DEX layout')
        for info in original.infolist():
            if signature_entry(info.filename):continue
            data=rebuilt.read(info.filename) if info.filename in original_dex else original.read(info.filename)
            target.writestr(info,data)
        for i,helper in enumerate(helper_dex,start=2):
            info=zipfile.ZipInfo(f'classes{i}.dex')
            info.compress_type=zipfile.ZIP_DEFLATED
            target.writestr(info,Path(helper).read_bytes())

def verify_stock_dex():
    from loguru import logger
    logger.remove()
    from androguard.core.dex import DEX
    with zipfile.ZipFile(SOURCE) as original, zipfile.ZipFile(STOCK_REBUILT) as rebuilt:
        original_classes={c.get_name():c for c in DEX(original.read('classes.dex')).get_classes()}
        patched_classes={c.get_name():c for c in DEX(rebuilt.read('classes.dex')).get_classes()}
    if set(original_classes)!=set(patched_classes):raise RuntimeError('Stock class inventory changed')
    unchanged=0; changed=[]
    allowed={('Lu0/A;','<init>','(Landroid/content/Context;)V'),
             ('Lcom/anbernic/control/service/ControlCenterService;','onCreate','()V')}
    def instructions(method):
        if not method.get_code():return []
        return [(i.get_name(),i.get_output()) for i in method.get_instructions()]
    for name,cls in original_classes.items():
        newer=patched_classes[name]
        old_methods={(m.get_name(),m.get_descriptor()):m for m in cls.get_methods()}
        new_methods={(m.get_name(),m.get_descriptor()):m for m in newer.get_methods()}
        if set(old_methods)!=set(new_methods):raise RuntimeError('Stock method inventory changed: '+name)
        old_fields={(f.get_name(),f.get_descriptor(),f.get_access_flags()) for f in cls.get_fields()}
        new_fields={(f.get_name(),f.get_descriptor(),f.get_access_flags()) for f in newer.get_fields()}
        if old_fields!=new_fields or cls.get_superclassname()!=newer.get_superclassname() or cls.get_interfaces()!=newer.get_interfaces():
            raise RuntimeError('Stock class structure changed: '+name)
        for key,method in old_methods.items():
            old=instructions(method);new=instructions(new_methods[key]);identity=(name,*key)
            if old!=new:
                if identity not in allowed:raise RuntimeError('Unexpected stock executable instructions changed: '+str(identity))
                callee='install' if name=='Lu0/A;' else 'boot'
                calls=[i for i,item in enumerate(new) if RUNTIME+'->'+callee in item[1]]
                if len(calls)!=1:raise RuntimeError('Missing/duplicate runtime hook')
                index=calls[0]
                start=index
                expected_names=['invoke-static']
                if [item[0] for item in new[start:index+1]]!=expected_names or new[:start]+new[index+1:]!=old:
                    raise RuntimeError('Hook method changed beyond the approved call insertion: '+str(identity))
                changed.append(identity)
            else:unchanged+=1
    if set(changed)!=allowed or unchanged!=29743:raise RuntimeError('Expected 29743 unchanged methods and exactly two hook methods')
    return {'stock_method_instructions_unchanged':unchanged,'only_changed_stock_methods':[list(x) for x in changed],
            'stock_class_fields_interfaces_superclasses_preserved':True}

def verify_artifacts(helper_dex):
    from loguru import logger
    logger.remove()
    from androguard.core.apk import APK
    from androguard.core.dex import DEX
    with zipfile.ZipFile(SOURCE) as original,zipfile.ZipFile(SIGNED) as final:
        unchanged=[]
        for name in original.namelist():
            if signature_entry(name) or re.fullmatch(r'classes\d*\.dex',name):continue
            if original.read(name)!=final.read(name):raise RuntimeError('Original entry changed or missing: '+name)
            unchanged.append(name)
        stock_classes={c.get_name():c for c in DEX(original.read('classes.dex')).get_classes()}
        new_stock_classes={c.get_name():c for c in DEX(final.read('classes.dex')).get_classes()}
        if set(stock_classes)!=set(new_stock_classes):raise RuntimeError('Original DEX class list changed')
        for name,cls in stock_classes.items():
            old_methods={(m.get_name(),m.get_descriptor(),m.get_access_flags()) for m in cls.get_methods()}
            new_methods={(m.get_name(),m.get_descriptor(),m.get_access_flags()) for m in new_stock_classes[name].get_methods()}
            if old_methods!=new_methods:raise RuntimeError('Original method list changed: '+name)
        helper_classes=set()
        for i,_ in enumerate(helper_dex,start=2):
            names={c.get_name() for c in DEX(final.read(f'classes{i}.dex')).get_classes()}
            if not names or any(not n.startswith('Lcom/anbernic/control/underclock/') for n in names):raise RuntimeError('Helper DEX includes classes outside approved namespace')
            if names&set(stock_classes) or names&helper_classes:raise RuntimeError('Duplicate DEX classes')
            helper_classes|=names
        if RUNTIME not in helper_classes:raise RuntimeError('Runtime helper missing')
        for owner,method_name,descriptor,callee in [
            ('Lu0/A;','<init>','(Landroid/content/Context;)V','install'),
            ('Lcom/anbernic/control/service/ControlCenterService;','onCreate','()V','boot')]:
            methods=[m for m in new_stock_classes[owner].get_methods() if m.get_name()==method_name and m.get_descriptor()==descriptor]
            if len(methods)!=1:raise RuntimeError('Patched method missing')
            hook=[i for i in methods[0].get_instructions() if RUNTIME+'->'+callee in i.get_output()]
            if len(hook)!=1:raise RuntimeError('Patched helper call missing/duplicated')
    apk=APK(str(SIGNED)); manifest=apk.get_android_manifest_xml()
    namespace='{http://schemas.android.com/apk/res/android}'
    if apk.get_package()!='com.anbernic.control' or apk.get_androidversion_code()!='9':raise RuntimeError('Package/version identity changed')
    if manifest.get(namespace+'sharedUserId')!='android.uid.system':raise RuntimeError('Shared UID changed')
    certificates=[sha(c.dump()) for c in apk.get_certificates()]
    if certificates!=[CERT_SHA256]:raise RuntimeError('Signer mismatch')
    if len(unchanged)!=1013:raise RuntimeError('Expected exactly 1013 preserved noncode entries')
    return {'all_original_noncode_entries_byte_identical':True,'preserved_entry_count':len(unchanged),
            'stock_classes_preserved':len(stock_classes),'helper_class_count':len(helper_classes),
            'stock_manifest_resources_byte_identical':True,'same_version_code':9,'same_shared_uid':'android.uid.system',
            'no_new_manifest_component_or_permission':True,'certificate_sha256':CERT_SHA256,
            'signed_apk_sha256':sha(SIGNED.read_bytes()),'signed_apk_bytes':SIGNED.stat().st_size,
            'signed_apk_path':str(SIGNED)}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stock-apk',type=Path,required=True,help='User-owned stock Control Center 1.0.29 APK')
    parser.add_argument('--apktool',type=Path,required=True,help='Official verified apktool_3.0.3.jar')
    parser.add_argument('--java',required=True,help='Java executable path, or a command available on PATH')
    parser.add_argument('--build-tools',type=Path,required=True,help='Android SDK build-tools directory, including zipalign and lib/apksigner.jar')
    parser.add_argument('--signing-key',type=Path,required=True,help='External matching platform test PKCS8 key; never copied into source')
    parser.add_argument('--signing-cert',type=Path,required=True,help='External matching platform.x509.pem certificate')
    parser.add_argument('--work-dir',type=Path,help='External build folder; defaults to a fresh OS temporary directory')
    parser.add_argument('--python-deps',type=Path,help='Optional directory containing an installed androguard dependency tree')
    parser.add_argument('--patch-only',action='store_true')
    parser.add_argument('--rebuild-only',action='store_true')
    parser.add_argument('--helper-dex',type=Path,action='append',required=True,help='Compiled helper DEX; repeat for multiple helper DEX files')
    args=parser.parse_args()
    configure(args)
    evidence=prepare()
    if args.patch_only:
        print(json.dumps(evidence,indent=2));return
    run([JAVA,'-jar',APKTOOL,'build',DECODED,'-o',STOCK_REBUILT])
    evidence.update(verify_stock_dex())
    if args.rebuild_only:
        print(json.dumps(evidence,indent=2));return
    if not args.helper_dex:raise RuntimeError('A supplied helper DEX is required before composing/signing a candidate')
    compose(args.helper_dex)
    run([ZIPALIGN,'-f','-P','16','4',COMPOSED,ALIGNED])
    run([JAVA,'-jar',BUILD_TOOLS/'lib/apksigner.jar','sign','--key',SIGNING_KEY,'--cert',SIGNING_CERT,
         '--v1-signing-enabled','true','--v2-signing-enabled','true','--v3-signing-enabled','true','--out',SIGNED,ALIGNED])
    sign_log=run([JAVA,'-jar',BUILD_TOOLS/'lib/apksigner.jar','verify','--verbose','--print-certs',SIGNED])
    (ROOT/'signature-verification.txt').write_text(sign_log,encoding='utf-8')
    run([ZIPALIGN,'-c','-P','16','4',SIGNED])
    evidence.update(verify_artifacts(args.helper_dex))
    (ROOT/'build-verification.json').write_text(json.dumps(evidence,indent=2),encoding='utf-8')
    print(json.dumps(evidence,indent=2))

if __name__=='__main__':main()
