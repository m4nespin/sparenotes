#include <arpa/inet.h>
#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define MAX_SESSION_BYTES (1024 * 1024)
#define SESSION_PATH "ch.proton.drive/drive-sdk-cli/auth-session"
#define STATUS_OK 0
#define STATUS_MISSING 1

static int write_all(int fd, const void *buffer, size_t length) {
    const unsigned char *position = buffer;
    while (length > 0) {
        ssize_t written = write(fd, position, length);
        if (written < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        position += written;
        length -= (size_t)written;
    }
    return 0;
}

static int read_all(int fd, void *buffer, size_t length) {
    unsigned char *position = buffer;
    while (length > 0) {
        ssize_t received = read(fd, position, length);
        if (received == 0) return -1;
        if (received < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        position += received;
        length -= (size_t)received;
    }
    return 0;
}

static int connect_vault(void) {
    const char *name = getenv("SPARENOTES_VAULT_SOCKET");
    if (name == NULL || name[0] == '\0') return -1;
    size_t name_length = strlen(name);
    if (name_length + 1 >= sizeof(((struct sockaddr_un *)0)->sun_path)) return -1;

    int socket_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (socket_fd < 0) return -1;
    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path + 1, name, name_length);
    socklen_t length = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + name_length);
    if (connect(socket_fd, (struct sockaddr *)&address, length) != 0) {
        close(socket_fd);
        return -1;
    }
    return socket_fd;
}

static int request(unsigned char operation, const unsigned char *payload, uint32_t payload_length) {
    int socket_fd = connect_vault();
    if (socket_fd < 0) return -1;
    uint32_t network_length = htonl(payload_length);
    if (write_all(socket_fd, &operation, 1) != 0
            || (operation == 2 && write_all(socket_fd, &network_length, sizeof(network_length)) != 0)
            || (payload_length > 0 && write_all(socket_fd, payload, payload_length) != 0)) {
        close(socket_fd);
        return -1;
    }

    unsigned char status;
    if (read_all(socket_fd, &status, 1) != 0
            || read_all(socket_fd, &network_length, sizeof(network_length)) != 0) {
        close(socket_fd);
        return -1;
    }
    uint32_t response_length = ntohl(network_length);
    if (response_length > MAX_SESSION_BYTES) {
        close(socket_fd);
        return -1;
    }
    unsigned char *response = response_length == 0 ? NULL : malloc(response_length);
    if (response_length > 0 && (response == NULL
            || read_all(socket_fd, response, response_length) != 0)) {
        free(response);
        close(socket_fd);
        return -1;
    }
    close(socket_fd);

    if (status == STATUS_OK) {
        int result = response_length == 0 || write_all(STDOUT_FILENO, response, response_length) == 0 ? 0 : -1;
        if (response != NULL) memset(response, 0, response_length);
        free(response);
        return result;
    }
    if (response != NULL) memset(response, 0, response_length);
    free(response);
    return status == STATUS_MISSING ? 1 : -1;
}

static unsigned char *read_stdin(uint32_t *length) {
    unsigned char *value = malloc(MAX_SESSION_BYTES);
    if (value == NULL) return NULL;
    size_t total = 0;
    while (total < MAX_SESSION_BYTES) {
        ssize_t received = read(STDIN_FILENO, value + total, MAX_SESSION_BYTES - total);
        if (received == 0) break;
        if (received < 0) {
            if (errno == EINTR) continue;
            free(value);
            return NULL;
        }
        total += (size_t)received;
    }
    unsigned char extra;
    if (total == MAX_SESSION_BYTES && read(STDIN_FILENO, &extra, 1) != 0) {
        memset(value, 0, total);
        free(value);
        return NULL;
    }
    *length = (uint32_t)total;
    return value;
}

int main(int argc, char **argv) {
    int result = -1;
    if (argc == 3 && strcmp(argv[1], "show") == 0 && strcmp(argv[2], SESSION_PATH) == 0) {
        result = request(1, NULL, 0);
        if (result == 1) {
            fprintf(stderr, "%s is not in the password store\n", SESSION_PATH);
            return 1;
        }
    } else if (argc == 5 && strcmp(argv[1], "insert") == 0
            && strcmp(argv[2], "-f") == 0 && strcmp(argv[3], "-m") == 0
            && strcmp(argv[4], SESSION_PATH) == 0) {
        uint32_t length = 0;
        unsigned char *value = read_stdin(&length);
        if (value != NULL) {
            result = request(2, value, length);
            memset(value, 0, length);
            free(value);
        }
    } else if (argc == 4 && strcmp(argv[1], "rm") == 0
            && strcmp(argv[2], "-f") == 0 && strcmp(argv[3], SESSION_PATH) == 0) {
        result = request(3, NULL, 0);
    }

    if (result != 0) {
        fputs("SpareNotes credential bridge failed\n", stderr);
        return 1;
    }
    return 0;
}
