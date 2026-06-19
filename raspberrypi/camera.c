#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <limits.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <signal.h>
#include <linux/videodev2.h>

struct buffer
{
    void *start;
    size_t length;
};

static pid_t start_gesture_controller(const char *python_command, const char *script_path)
{
    pid_t pid = fork();
    if (pid < 0) {
        perror("fork media_gesture.py");
        return -1;
    }
    if (pid == 0) {
        execlp(python_command, python_command, script_path, (char *)NULL);
        perror("exec media_gesture.py");
        _exit(127);
    }
    return pid;
}

int main(void)
{
    int fd;
    struct v4l2_format fmt;
    struct v4l2_requestbuffers req;
    struct v4l2_buffer buf;
    struct buffer buffer;
    enum v4l2_buf_type type;
    char project_directory[PATH_MAX];
    char home_directory[PATH_MAX];
    char capture_path[PATH_MAX];
    char python_path[PATH_MAX];
    char gesture_script_path[PATH_MAX];
    char analyzer_command[PATH_MAX * 4];

    if (getcwd(project_directory, sizeof(project_directory)) == NULL) {
        perror("getcwd");
        return 1;
    }

    const char *home = getenv("HOME");
    if (home == NULL || home[0] == '\0') {
        fprintf(stderr, "HOME environment variable is not set\n");
        return 1;
    }
    snprintf(home_directory, sizeof(home_directory), "%s", home);

    snprintf(capture_path, sizeof(capture_path), "%s/capture.jpg", home_directory);
    snprintf(python_path, sizeof(python_path), "%s/.venv/bin/python", project_directory);
    snprintf(
        gesture_script_path,
        sizeof(gesture_script_path),
        "%s/media_gesture.py",
        project_directory
    );
    const char *python_command = access(python_path, X_OK) == 0 ? python_path : "python3";

    // 1. カメラのオープンと初期設定
    fd = open("/dev/video0", O_RDWR);
    if (fd < 0) {
        perror("open");
        return 1;
    }

    memset(&fmt, 0, sizeof(fmt));
    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    fmt.fmt.pix.width = 640;
    fmt.fmt.pix.height = 480;
    fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_MJPEG;
    fmt.fmt.pix.field = V4L2_FIELD_ANY;

    if (ioctl(fd, VIDIOC_S_FMT, &fmt) < 0) {
        perror("VIDIOC_S_FMT");
        return 1;
    }

    memset(&req, 0, sizeof(req));
    req.count = 1;
    req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    req.memory = V4L2_MEMORY_MMAP;

    if (ioctl(fd, VIDIOC_REQBUFS, &req) < 0) {
        perror("VIDIOC_REQBUFS");
        return 1;
    }

    memset(&buf, 0, sizeof(buf));
    buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    buf.memory = V4L2_MEMORY_MMAP;
    buf.index = 0;

    if (ioctl(fd, VIDIOC_QUERYBUF, &buf) < 0) {
        perror("VIDIOC_QUERYBUF");
        return 1;
    }

    buffer.length = buf.length;
    buffer.start = mmap(NULL, buf.length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, buf.m.offset);

    if (buffer.start == MAP_FAILED) {
        perror("mmap");
        return 1;
    }

    type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    printf("自動監視システムを起動します（終了するには Ctrl + C を押してください）\n\n");
    pid_t gesture_pid = start_gesture_controller(python_command, gesture_script_path);

    // ==========================================
    // 🌟 ここから無限ループ（5秒ごとの自動実行）
    // ==========================================
    while(1) {
        // 毎回新しくバッファをセットする
        if (ioctl(fd, VIDIOC_QBUF, &buf) < 0) {
            perror("VIDIOC_QBUF");
            break;
        }

        // ストリーム開始（ここで最新の映像が取得される）
        if (ioctl(fd, VIDIOC_STREAMON, &type) < 0) {
            perror("VIDIOC_STREAMON");
            break;
        }

        // 画像データを引き出す
        if (ioctl(fd, VIDIOC_DQBUF, &buf) < 0) {
            perror("VIDIOC_DQBUF");
            break;
        }

        // 画像ファイルとして保存
        FILE *fp = fopen(capture_path, "wb");
        if (!fp) {
            perror("fopen");
            break;
        }
        fwrite(buffer.start, 1, buf.bytesused, fp);
        fclose(fp);

        printf(" 写真を更新しました (%u bytes)\n", buf.bytesused);

        // ストリーム停止（次の5秒間、不要なフレームを溜め込まないようにする）
        ioctl(fd, VIDIOC_STREAMOFF, &type);

        // 直前の写真を解析する
        printf("--- 顔向きとQRコードを解析中 ---\n");
        snprintf(
            analyzer_command,
            sizeof(analyzer_command),
            "\"%s\" \"%s/face_analyzer.py\" --once \"%s\"",
            python_command,
            project_directory,
            capture_path
        );
        int analyzer_status = system(analyzer_command);
        if (analyzer_status != 0) {
            fprintf(stderr, "face_analyzer.py failed with status %d\n", analyzer_status);
        }
        printf("-------------------------------\n\n");

        // 2秒待機（画像更新間隔）
        sleep(2);
    }
    // ==========================================
    // ループ終了・後片付け
    // ==========================================

    munmap(buffer.start, buffer.length);
    close(fd);

    if (gesture_pid > 0) {
        kill(gesture_pid, SIGTERM);
        waitpid(gesture_pid, NULL, 0);
    }

    return 0;
}
