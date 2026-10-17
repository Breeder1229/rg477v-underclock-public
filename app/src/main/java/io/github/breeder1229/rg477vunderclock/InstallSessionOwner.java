package io.github.breeder1229.rg477vunderclock;
/** Shared UID alone never establishes session ownership. */
final class InstallSessionOwner {
    static boolean matches(String installer,String target,String ownPackage) {
        return ownPackage!=null&&!ownPackage.isEmpty()&&ownPackage.equals(installer)
                &&"com.anbernic.control".equals(target);
    }
    private InstallSessionOwner() { }
}
