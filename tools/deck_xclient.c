#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define WINDOW_X 80
#define WINDOW_Y 80
#define WINDOW_W 620
#define WINDOW_H 420

#define EVENT_KEY_PRESS 2
#define EVENT_BUTTON_PRESS 4
#define EVENT_EXPOSE 12
#define EVENT_MAP_NOTIFY 19
#define EVENT_ERROR 0

static int display_fd = -1;
static uint32_t root_window;
static uint32_t root_visual;
static uint32_t resource_base;
static uint32_t resource_mask;
static uint8_t root_depth;
static uint16_t root_width;
static uint16_t root_height;

static uint32_t window_id;
static uint32_t black_gc;
static uint32_t teal_gc;
static uint32_t white_gc;
static uint32_t accent_gc;

static int last_x = -1;
static int last_y = -1;

static uint16_t read_u16(const uint8_t *p) {
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

static uint32_t read_u32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static void write_u16(uint8_t *p, uint16_t value) {
    p[0] = (uint8_t)(value & 0xff);
    p[1] = (uint8_t)((value >> 8) & 0xff);
}

static void write_u32(uint8_t *p, uint32_t value) {
    p[0] = (uint8_t)(value & 0xff);
    p[1] = (uint8_t)((value >> 8) & 0xff);
    p[2] = (uint8_t)((value >> 16) & 0xff);
    p[3] = (uint8_t)((value >> 24) & 0xff);
}

static int write_all(const void *buffer, size_t length) {
    const uint8_t *cursor = buffer;
    while (length > 0) {
        ssize_t written = send(display_fd, cursor, length, MSG_NOSIGNAL);
        if (written < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        cursor += written;
        length -= (size_t)written;
    }
    return 0;
}

static int read_all(void *buffer, size_t length) {
    uint8_t *cursor = buffer;
    while (length > 0) {
        ssize_t count = recv(display_fd, cursor, length, 0);
        if (count < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (count == 0) return -1;
        cursor += count;
        length -= (size_t)count;
    }
    return 0;
}

static int send_request(uint8_t *request, size_t length) {
    if ((length & 3u) != 0) return -1;
    write_u16(request + 2, (uint16_t)(length / 4));
    return write_all(request, length);
}

static int connect_display(const char *path) {
    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    if (strlen(path) >= sizeof(address.sun_path)) {
        fprintf(stderr, "socket path too long: %s\n", path);
        return -1;
    }
    strcpy(address.sun_path, path);

    for (int attempt = 0; attempt < 50; attempt++) {
        display_fd = socket(AF_UNIX, SOCK_STREAM, 0);
        if (display_fd < 0) return -1;
        if (connect(display_fd, (struct sockaddr *)&address, sizeof(address)) == 0) {
            return 0;
        }
        close(display_fd);
        display_fd = -1;
        struct timespec pause = {0, 100000000};
        nanosleep(&pause, NULL);
    }
    fprintf(stderr, "cannot connect to %s: %s\n", path, strerror(errno));
    return -1;
}

static int setup_display(void) {
    uint8_t request[12];
    memset(request, 0, sizeof(request));
    request[0] = 'l';
    write_u16(request + 2, 11);
    if (write_all(request, sizeof(request)) != 0) return -1;

    uint8_t header[8];
    if (read_all(header, sizeof(header)) != 0) return -1;
    if (header[0] != 1) {
        uint16_t reason_length = read_u16(header + 6);
        char reason[256];
        memset(reason, 0, sizeof(reason));
        size_t bytes = (size_t)reason_length * 4;
        if (bytes >= sizeof(reason)) bytes = sizeof(reason) - 1;
        if (bytes > 0) read_all(reason, bytes);
        fprintf(stderr, "display setup failed: %s\n", reason);
        return -1;
    }

    uint16_t additional_length = read_u16(header + 6);
    size_t total = (size_t)additional_length * 4;
    uint8_t *data = malloc(total);
    if (data == NULL) return -1;
    if (read_all(data, total) != 0) {
        free(data);
        return -1;
    }

    if (total < 40) {
        free(data);
        return -1;
    }

    resource_base = read_u32(data + 4);
    resource_mask = read_u32(data + 8);
    uint16_t vendor_length = read_u16(data + 16);
    uint8_t screen_count = data[20];
    uint8_t format_count = data[21];
    (void)screen_count;

    size_t offset = 32;
    offset += ((size_t)vendor_length + 3u) & ~3u;
    offset += (size_t)format_count * 8u;
    if (offset + 40 > total) {
        free(data);
        return -1;
    }

    root_window = read_u32(data + offset);
    root_width = read_u16(data + offset + 20);
    root_height = read_u16(data + offset + 22);
    root_visual = read_u32(data + offset + 32);
    root_depth = data[offset + 38];

    free(data);

    if (root_window == 0 || root_visual == 0 || root_depth == 0) {
        fprintf(stderr, "invalid display setup\n");
        return -1;
    }

    fprintf(stderr, "connected root=0x%08x visual=0x%08x depth=%u screen=%ux%u\n",
            root_window, root_visual, root_depth, root_width, root_height);
    return 0;
}

static uint32_t allocate_id(uint32_t offset) {
    return resource_base | ((resource_mask ? (offset & resource_mask) : offset));
}

static int create_window(void) {
    uint8_t request[40];
    memset(request, 0, sizeof(request));
    window_id = allocate_id(1);

    request[0] = 1;
    request[1] = root_depth;
    write_u32(request + 4, window_id);
    write_u32(request + 8, root_window);
    write_u16(request + 12, WINDOW_X);
    write_u16(request + 14, WINDOW_Y);
    write_u16(request + 16, WINDOW_W);
    write_u16(request + 18, WINDOW_H);
    write_u16(request + 20, 0);
    write_u16(request + 22, 1);
    write_u32(request + 24, root_visual);
    write_u32(request + 28, 0x0002u | 0x0800u);
    write_u32(request + 32, 0x00000000u);
    write_u32(request + 36, 0x00000001u | 0x00000004u | 0x00008000u | 0x00020000u);
    return send_request(request, sizeof(request));
}

static int create_gcs(void) {
    black_gc = allocate_id(2);
    teal_gc = allocate_id(3);
    white_gc = allocate_id(4);
    accent_gc = allocate_id(5);

    uint32_t ids[4] = {black_gc, teal_gc, white_gc, accent_gc};
    uint32_t colors[4] = {0x00000000u, 0x0000d7c6u, 0x00f4f7fbu, 0x00ffb020u};

    for (int index = 0; index < 4; index++) {
        uint8_t request[28];
        memset(request, 0, sizeof(request));
        request[0] = 55;
        write_u32(request + 4, ids[index]);
        write_u32(request + 8, window_id);
        write_u32(request + 12, 0x0004u | 0x0008u | 0x0010u);
        write_u32(request + 16, colors[index]);
        write_u32(request + 20, 0x00000000u);
        write_u32(request + 24, 1u);
        if (send_request(request, sizeof(request)) != 0) return -1;
    }
    return 0;
}

static int map_window(void) {
    uint8_t request[8];
    memset(request, 0, sizeof(request));
    request[0] = 8;
    write_u32(request + 4, window_id);
    return send_request(request, sizeof(request));
}

static int clear_window(void) {
    uint8_t request[16];
    memset(request, 0, sizeof(request));
    request[0] = 61;
    write_u32(request + 4, window_id);
    write_u16(request + 8, 0);
    write_u16(request + 10, 0);
    write_u16(request + 12, WINDOW_W);
    write_u16(request + 14, WINDOW_H);
    return send_request(request, sizeof(request));
}

static int fill_rect(uint32_t gc, int x, int y, int width, int height) {
    uint8_t request[20];
    memset(request, 0, sizeof(request));
    request[0] = 70;
    write_u32(request + 4, window_id);
    write_u32(request + 8, gc);
    write_u16(request + 12, (uint16_t)x);
    write_u16(request + 14, (uint16_t)y);
    write_u16(request + 16, (uint16_t)width);
    write_u16(request + 18, (uint16_t)height);
    return send_request(request, sizeof(request));
}

static void draw_scene(void) {
    clear_window();
    fill_rect(teal_gc, 24, 24, WINDOW_W - 48, 112);
    fill_rect(white_gc, 24, 158, WINDOW_W - 48, 2);
    fill_rect(white_gc, 24, 190, 360, 54);
    fill_rect(white_gc, 24, 272, 250, 40);
    if (last_x >= 0 && last_y >= 0) {
        fill_rect(accent_gc, last_x - 36, last_y - 36, 72, 72);
    }
}

static int handle_event(const uint8_t *event) {
    int type = event[0] & 0x7fu;

    if (type == EVENT_EXPOSE) {
        uint16_t count = read_u16(event + 16);
        if (count == 0) draw_scene();
        return 0;
    }

    if (type == EVENT_BUTTON_PRESS) {
        int x = (int)read_u16(event + 24);
        int y = (int)read_u16(event + 26);
        last_x = x;
        last_y = y;
        fprintf(stderr, "button press x=%d y=%d\n", x, y);
        draw_scene();
        return 0;
    }

    if (type == EVENT_KEY_PRESS) {
        fprintf(stderr, "key press keycode=%u\n", (unsigned)event[1]);
        return 0;
    }

    if (type == EVENT_MAP_NOTIFY) {
        draw_scene();
        return 0;
    }

    if (type == EVENT_ERROR) {
        fprintf(stderr, "x11 error code=%u sequence=%u bad=0x%08x\n",
                (unsigned)event[1], (unsigned)read_u16(event + 2),
                read_u32(event + 4));
        return -1;
    }

    return 0;
}

static void stop_client(int signal_number) {
    (void)signal_number;
    if (display_fd >= 0) close(display_fd);
    _exit(0);
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: %s /path/to/.X11-unix/X1\n", argv[0]);
        return 2;
    }

    signal(SIGTERM, stop_client);
    signal(SIGINT, stop_client);

    if (connect_display(argv[1]) != 0) return 1;
    if (setup_display() != 0) return 1;
    if (create_window() != 0) return 1;
    if (create_gcs() != 0) return 1;
    if (map_window() != 0) return 1;

    fprintf(stderr, "test window mapped id=0x%08x\n", window_id);

    uint8_t event[32];
    while (read_all(event, sizeof(event)) == 0) {
        if (handle_event(event) != 0) break;
    }

    fprintf(stderr, "display connection closed\n");
    return 0;
}
