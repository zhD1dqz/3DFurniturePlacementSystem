#include <vulkan/vulkan.h>
#include <stdio.h>
#include <stdlib.h>

static void json_string(const char *s) {
    putchar('"');
    for (; *s; ++s) {
        if (*s == '"' || *s == '\\') putchar('\\');
        if ((unsigned char)*s < 32) printf("\\u%04x", (unsigned char)*s);
        else putchar(*s);
    }
    putchar('"');
}

int main(void) {
    VkApplicationInfo app = {.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO, .pApplicationName="Furniture GPU probe", .apiVersion=VK_API_VERSION_1_1};
    VkInstanceCreateInfo create = {.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo=&app};
    VkInstance instance;
    VkResult result = vkCreateInstance(&create, NULL, &instance);
    if (result != VK_SUCCESS) { printf("{\"error\":\"vkCreateInstance\",\"code\":%d}\n",result); return 1; }
    uint32_t count=0;
    vkEnumeratePhysicalDevices(instance,&count,NULL);
    VkPhysicalDevice *devices=calloc(count,sizeof(VkPhysicalDevice));
    vkEnumeratePhysicalDevices(instance,&count,devices);
    printf("{\"vulkan_devices\":[");
    for (uint32_t i=0;i<count;i++) {
        VkPhysicalDeviceSubgroupProperties subgroup={.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES};
        VkPhysicalDeviceProperties2 props={.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2,.pNext=&subgroup};
        vkGetPhysicalDeviceProperties2(devices[i],&props);
        VkPhysicalDeviceFeatures2 features={.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
        vkGetPhysicalDeviceFeatures2(devices[i],&features);
        if (i) putchar(',');
        printf("{\"name\":");json_string(props.properties.deviceName);
        printf(",\"api_version\":\"%u.%u.%u\",\"driver_version_raw\":%u,\"subgroup_size\":%u,\"compute_subgroups_supported\":%s,\"max_compute_invocations\":%u,\"max_workgroup_storage_bytes\":%u,\"max_storage_buffer_bytes\":%u,\"shader_int64\":%s}",
            VK_VERSION_MAJOR(props.properties.apiVersion),VK_VERSION_MINOR(props.properties.apiVersion),VK_VERSION_PATCH(props.properties.apiVersion),
            props.properties.driverVersion,subgroup.subgroupSize,(subgroup.supportedStages&VK_SHADER_STAGE_COMPUTE_BIT)?"true":"false",
            props.properties.limits.maxComputeWorkGroupInvocations,props.properties.limits.maxComputeSharedMemorySize,
            props.properties.limits.maxStorageBufferRange,features.features.shaderInt64?"true":"false");
    }
    printf("],\"scope\":\"Capability query only; not a training benchmark\"}\n");
    free(devices);vkDestroyInstance(instance,NULL);
    return count?0:2;
}
