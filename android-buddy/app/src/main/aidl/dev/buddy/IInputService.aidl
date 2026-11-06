package dev.buddy;

// The whole surface of Buddy's privileged helper (a Shizuku UserService): typed taps, swipes and whitelisted key presses.
// There is deliberately no generic "run this" method. Each call returns true if the input was injected.
interface IInputService {
    void destroy() = 16777114; // reserved by Shizuku: stops the service process

    boolean tap(int x, int y) = 1;
    boolean longPress(int x, int y) = 2;
    boolean doubleTap(int x, int y) = 3;
    boolean swipe(int x1, int y1, int x2, int y2, int ms) = 4;
    boolean key(int keyCode) = 5;
}
