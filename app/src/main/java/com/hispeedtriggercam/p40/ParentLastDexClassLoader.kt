package com.hispeedtriggercam.p40

import dalvik.system.DexClassLoader

/**
 * A DexClassLoader that loads `com.huawei.camerakit.impl.*` classes from its own
 * dex FIRST, before delegating to the parent classloader.
 *
 * WHY THIS IS NEEDED:
 * On Huawei devices, HwCameraKit.apk is loaded as a shared library whose classloader
 * becomes the parent of the app classloader. Standard parent-first delegation means
 * the system's (unpatched) implementation classes are always found first, shadowing
 * our patched version that's compiled into the APK.
 *
 * HOW IT WORKS:
 * - For `impl.*` classes: load from our embedded dex first (contains patched HwMediaRecorder)
 * - For everything else (`api.*`, `foundation.*`, framework): delegate to parent normally
 *
 * This ensures type compatibility: API interfaces (ModeInterface, ModeStateCallback, etc.)
 * are the same class objects across both the system and our patched impl classes.
 */
class ParentLastDexClassLoader(
    dexPath: String,
    parent: ClassLoader
) : DexClassLoader(dexPath, null, null, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // Already loaded by this classloader?
        findLoadedClass(name)?.let { return it }

        // For implementation classes: try our patched dex first
        if (name.startsWith("com.huawei.camerakit.impl")) {
            try {
                return findClass(name)
            } catch (_: ClassNotFoundException) {
                // Not in our dex, fall through to parent
            }
        }

        // Everything else: delegate to parent (API interfaces, foundation, framework)
        return parent.loadClass(name)
    }
}
