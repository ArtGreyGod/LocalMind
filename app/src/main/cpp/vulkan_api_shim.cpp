// Vulkan loader shim for Android.
//
// ggml-vulkan calls the promoted core-1.1 entrypoint `vkGetPhysicalDeviceFeatures2`
// directly. Android's Vulkan loader only starts exporting core-1.1 symbols from API 28,
// so builds with minSdk < 28 fail to link. This shim provides the missing symbol and
// forwards to the real loader export at runtime (core name on API 28+, falling back to
// the identical, always-exported KHR entrypoint on older devices).
//
// The alias define in CMakeLists.txt is NOT usable here: renaming the symbol via macros
// duplicates members inside vulkan.hpp's dispatch tables, so the shim must keep the
// original symbol name while remaining ABI-compatible (all args are pointers).

#include <dlfcn.h>

namespace {

typedef void (*vkGetPhysicalDeviceFeatures2_ptr_t)(void * /*physicalDevice*/, void * /*pFeatures*/);

vkGetPhysicalDeviceFeatures2_ptr_t resolve_real_vkGetPhysicalDeviceFeatures2() {
    static vkGetPhysicalDeviceFeatures2_ptr_t resolved = nullptr;
    if (resolved == nullptr) {
        // The loader is already loaded into this process (linked by the Vulkan backend);
        // dlopen simply takes a reference to it.
        void *handle = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        if (handle != nullptr) {
            resolved = reinterpret_cast<vkGetPhysicalDeviceFeatures2_ptr_t>(
                    dlsym(handle, "vkGetPhysicalDeviceFeatures2"));
            if (resolved == nullptr) {
                // API 26/27 loaders export only the promoted-from-KHR variant.
                resolved = reinterpret_cast<vkGetPhysicalDeviceFeatures2_ptr_t>(
                        dlsym(handle, "vkGetPhysicalDeviceFeatures2KHR"));
            }
        }
    }
    return resolved;
}

} // namespace

extern "C" __attribute__((visibility("default"))) void vkGetPhysicalDeviceFeatures2(
        void *physicalDevice, void *pFeatures) {
    auto fn = resolve_real_vkGetPhysicalDeviceFeatures2();
    if (fn != nullptr) {
        fn(physicalDevice, pFeatures);
    }
}
