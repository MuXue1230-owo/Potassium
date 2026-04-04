const int POTASSIUM_WORLD_DATA_RW_MAX_SHADER_PAGES = 8;
const uint POTASSIUM_WORLD_DATA_RW_MAX_SHADER_PAGES_U = 8u;

layout(std430, binding = 0) readonly buffer PotassiumWorldDataLayoutBuffer {
    uvec4 header;
    ivec4 worldInfo;
    uvec4 pageInfo[POTASSIUM_WORLD_DATA_RW_MAX_SHADER_PAGES];
} potassium_world_data_layout;

layout(std430, binding = 1) buffer PotassiumWorldDataPage0Buffer {
    uint blocks[];
} potassium_world_data_page0;

layout(std430, binding = 2) buffer PotassiumWorldDataPage1Buffer {
    uint blocks[];
} potassium_world_data_page1;

layout(std430, binding = 3) buffer PotassiumWorldDataPage2Buffer {
    uint blocks[];
} potassium_world_data_page2;

layout(std430, binding = 4) buffer PotassiumWorldDataPage3Buffer {
    uint blocks[];
} potassium_world_data_page3;

layout(std430, binding = 5) buffer PotassiumWorldDataPage4Buffer {
    uint blocks[];
} potassium_world_data_page4;

layout(std430, binding = 6) buffer PotassiumWorldDataPage5Buffer {
    uint blocks[];
} potassium_world_data_page5;

layout(std430, binding = 7) buffer PotassiumWorldDataPage6Buffer {
    uint blocks[];
} potassium_world_data_page6;

layout(std430, binding = 8) buffer PotassiumWorldDataPage7Buffer {
    uint blocks[];
} potassium_world_data_page7;

uint potassium_world_bytes_per_chunk() {
    return potassium_world_data_layout.header.x;
}

uint potassium_world_blocks_per_chunk() {
    return potassium_world_data_layout.header.y;
}

uint potassium_world_shader_visible_page_count() {
    return min(potassium_world_data_layout.header.z, POTASSIUM_WORLD_DATA_RW_MAX_SHADER_PAGES_U);
}

uint potassium_world_shader_visible_chunk_capacity() {
    uint pageCount = potassium_world_shader_visible_page_count();
    uint chunkCapacity = 0u;
    for (uint index = 0u; index < pageCount; index++) {
        chunkCapacity += potassium_world_data_layout.pageInfo[index].w;
    }

    return chunkCapacity;
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

bool potassium_world_store_packed_block(uint logicalBlockIndex, uint packedBlock) {
    uint pageIndex;
    uint localBlockIndex;
    if (!potassium_world_resolve_block(logicalBlockIndex, pageIndex, localBlockIndex)) {
        return false;
    }

    switch (pageIndex) {
        case 0u:
            potassium_world_data_page0.blocks[localBlockIndex] = packedBlock;
            return true;
        case 1u:
            potassium_world_data_page1.blocks[localBlockIndex] = packedBlock;
            return true;
        case 2u:
            potassium_world_data_page2.blocks[localBlockIndex] = packedBlock;
            return true;
        case 3u:
            potassium_world_data_page3.blocks[localBlockIndex] = packedBlock;
            return true;
        case 4u:
            potassium_world_data_page4.blocks[localBlockIndex] = packedBlock;
            return true;
        case 5u:
            potassium_world_data_page5.blocks[localBlockIndex] = packedBlock;
            return true;
        case 6u:
            potassium_world_data_page6.blocks[localBlockIndex] = packedBlock;
            return true;
        case 7u:
            potassium_world_data_page7.blocks[localBlockIndex] = packedBlock;
            return true;
        default:
            return false;
    }
}

uint potassium_world_chunk_base_block(uint residentSlot) {
    return residentSlot * potassium_world_blocks_per_chunk();
}
