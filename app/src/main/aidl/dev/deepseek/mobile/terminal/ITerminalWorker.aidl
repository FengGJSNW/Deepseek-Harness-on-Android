package dev.deepseek.mobile.terminal;

interface ITerminalWorker {
    String info() = 0;
    String start(String spec) = 1;
    String poll(String id) = 2;
    void cancel(String id) = 3;
    String inspect(String spec) = 4;
    String readOnlyProbe() = 5;
    void destroy() = 16777114;
}
