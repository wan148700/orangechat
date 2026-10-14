package me.rerere.rikkahub.data.shizuku;
interface IShizukuProbe {
    String probe() = 0;
    String execute(String command, int timeoutSeconds, int maxOutputChars) = 1;
    void destroy() = 16777114;
}
