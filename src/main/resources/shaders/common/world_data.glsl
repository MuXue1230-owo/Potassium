const int POTASSIUM_WORLD_DATA_MAX_SHADER_PAGES = 4;
const uint POTASSIUM_WORLD_DATA_MAX_SHADER_PAGES_U = 4u;
const uint POTASSIUM_BLOCK_STATE_ID_MASK = (1u << 20u) - 1u;
const uint POTASSIUM_BLOCK_FLAGS_MASK = (1u << 4u) - 1u;
const uint POTASSIUM_BLOCK_FLAGS_SHIFT = 28u;
const uint POTASSIUM_BLOCK_FLAG_OCCLUDES = 1u << 0u;
const uint POTASSIUM_BLOCK_FLAG_FLUID = 1u << 1u;
const uint POTASSIUM_BLOCK_FLAG_TRANSLUCENT = 1u << 2u;

layout(std430, binding = 0) readonly buffer PotassiumWorldDataLayoutBuffer {
    uvec4 header;
    uvec4 pageInfo[POTASSIUM_WORLD_DATA_MAX_SHADER_PAGES];
} potassium_world_data_layout;

layout(std430, binding = 1) readonly buffer PotassiumWorldDataPage0Buffer {
    uint blocks[];
} potassium_world_data_page0;

layout(std430, binding = 2) readonly buffer PotassiumWorldDataPage1Buffer {
    uint blocks[];
} potassium_world_data_page1;

layout(std430, binding = 3) readonly buffer PotassiumWorldDataPage2Buffer {
    uint blocks[];
} potassium_world_data_page2;

layout(std430, binding = 4) readonly buffer PotassiumWorldDataPage3Buffer {
    uint blocks[];
} potassium_world_data_page3;

uint potassium_world_bytes_per_chunk() {
    return potassium_world_data_layout.header.x;
}

uint potassium_world_blocks_per_chunk() {
    return potassium_world_data_layout.header.y;
}

uint potassium_world_shader_visible_page_count() {
    return min(potassium_world_data_layout.header.z, POTASSIUM_WORLD_DATA_MAX_SHADER_PAGES_U);
}

bool potassium_world_resolve_block(uint logicalBlockIndex, out uint pageIndex, out uint localBlockIndex) {
    uint pageCount = potassium_world_shader_visible_page_count();
    for (uint index = 0u; index < pageCount; index++) {
        uvec4 page = potassium_world_data_layout.pageInfo[index];
        uint startBlock = page.x;
        uint blockCount = page.y;
        if (logicalBlockIndex >= startBlock && logicalBlockIndex < (startBlock + blockCount)) {
            pageIndex = index;
            localBlockIndex = logicalBlockIndex - startBlock;
            return true;
        }
    }

    pageIndex = 0u;
    localBlockIndex = 0u;
    return false;
}

uint potassium_world_load_packed_block(uint logicalBlockIndex) {
    uint pageIndex;
    uint localBlockIndex;
    if (!potassium_world_resolve_block(logicalBlockIndex, pageIndex, localBlockIndex)) {
        return 0u;
    }

    switch (pageIndex) {
        case 0u:
            return potassium_world_data_page0.blocks[localBlockIndex];
        case 1u:
            return potassium_world_data_page1.blocks[localBlockIndex];
        case 2u:
            return potassium_world_data_page2.blocks[localBlockIndex];
        case 3u:
            return potassium_world_data_page3.blocks[localBlockIndex];
        default:
            return 0u;
    }
}

uint potassium_world_chunk_base_block(uint residentSlot) {
    return residentSlot * potassium_world_blocks_per_chunk();
}

uint potassium_block_state_id(uint packedBlock) {
    return packedBlock & POTASSIUM_BLOCK_STATE_ID_MASK;
}

uint potassium_block_flags(uint packedBlock) {
    return (packedBlock >> POTASSIUM_BLOCK_FLAGS_SHIFT) & POTASSIUM_BLOCK_FLAGS_MASK;
}

bool potassium_block_is_air(uint packedBlock) {
    return potassium_block_state_id(packedBlock) == 0u;
}

bool potassium_block_occludes(uint packedBlock) {
    return (potassium_block_flags(packedBlock) & POTASSIUM_BLOCK_FLAG_OCCLUDES) != 0u;
}

bool potassium_block_is_fluid(uint packedBlock) {
    return (potassium_block_flags(packedBlock) & POTASSIUM_BLOCK_FLAG_FLUID) != 0u;
}

bool potassium_block_is_translucent(uint packedBlock) {
    return (potassium_block_flags(packedBlock) & POTASSIUM_BLOCK_FLAG_TRANSLUCENT) != 0u;
}
