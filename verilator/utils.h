
void generate_random_uint16(uint16_t *data, uint16_t range, int num) {
    for (int i = 0; i < num; i++) {
        data[i] = rand() % range;
    }
}

void generate_seq_uint16(uint16_t *data, uint16_t range, int num, bool reverse=false) {
    for (int i = 0; i < num; i++) {
        data[i] = reverse ? (range - 1 - i % range) : (i % range);
    }
}

void bit_pack_uint16_to_uint64(uint16_t *data, uint64_t *packed, int num_packed) {
    for (int i = 0; i < num_packed; i++) {
        packed[i] = 0;
        for(int j = 0; j < 4; j++) {
            packed[i] |= (uint64_t)data[i * 4 + j] << (j * 16);
        }
    }
}

void bit_unpack_uint64_to_uint16(uint64_t *packed, uint16_t *data, int num_packed) {
    for (int i = 0; i < num_packed; i++) {
        for(int j = 0; j < 4; j++) {
            data[i * 4 + j] = (packed[i] >> (j * 16)) & 0xffff;
        }
    }
}

void print_uint16(uint16_t *data, int num) {
    for (int i = 0; i < num; i++) {
        printf("%d\t", data[i]);
    }
    printf("\n");
}

void print_uint64(uint64_t *data, int num) {
    for (int i = 0; i < num; i++) {
        printf("%016lx\t", data[i]);
    }
    printf("\n");
}

bool check_gather_result(uint16_t *gt, uint16_t *out, int num) {
    for(int i = 0; i < num; i++) {
        if(gt[i] != out[i]) {
            return false;
        }
    }
    return true;
}

