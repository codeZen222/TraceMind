package org.example.tracemind.common;

import java.security.SecureRandom;

public class IdGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int LENGTH = 8;

    private IdGenerator() {
    }

    public static String project()    { return next("p"); }
    public static String log()        { return next("log"); }
    public static String session()    { return next("sess"); }
    public static String task()       { return next("t"); }
    public static String importTask() { return next("imp"); }
    public static String report()     { return next("r"); }
    public static String chat()       { return next("cm"); }
    public static String user()       { return next("u"); }

    private static String next(String prefix) {
        StringBuilder sb = new StringBuilder(prefix.length() + 1 + LENGTH);
        sb.append(prefix).append('-');
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
