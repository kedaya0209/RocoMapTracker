package com.luoke.app.ui.service.resource;

import net.jcip.annotations.ThreadSafe;
import javafx.scene.shape.SVGPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SVG 路径长度计算 + d 字符串 tokenizer。
 * 支持 M/L/C/Q/A/Z/H/V/S/T 及小写相对版本，贝塞尔曲线采样 20 段近似。
 */
@ThreadSafe
final class SvgPathUtil {

    private static final Pattern SVG_TOKEN = Pattern.compile(
            "[MLCQAZHVSTmlcqazhvst]|[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?");

    private static final Map<String, Double> pathLengthCache = new ConcurrentHashMap<>();

    private SvgPathUtil() {
    }

    static double computePathLength(SVGPath path) {
        return pathLengthCache.computeIfAbsent(path.getContent(), SvgPathUtil::computeLength);
    }

    static void clearCache() {
        pathLengthCache.clear();
    }

    // ================================================================
    // 路径长度计算
    // ================================================================

    private static double computeLength(String d) {
        List<Object> tokens = tokenize(d);
        double[] pos = {0, 0};
        double[] start = {0, 0};
        double total = 0;
        int idx = 0;

        double prevCpX = 0, prevCpY = 0;
        Cmd prevCmd = null;

        while (idx < tokens.size()) {
            Object t = tokens.get(idx++);
            if (!(t instanceof Character cmdChar)) continue;

            Cmd cmd = switch (Character.toUpperCase(cmdChar)) {
                case 'M' -> Cmd.M;
                case 'L' -> Cmd.L;
                case 'C' -> Cmd.C;
                case 'Q' -> Cmd.Q;
                case 'A' -> Cmd.A;
                case 'Z' -> Cmd.Z;
                case 'H' -> Cmd.H;
                case 'V' -> Cmd.V;
                case 'S' -> Cmd.S;
                case 'T' -> Cmd.T;
                default -> throw new IllegalArgumentException("Unknown command: " + cmdChar);
            };
            boolean rel = Character.isLowerCase(cmdChar);

            if (cmd == Cmd.Z) {
                total += Math.hypot(start[0] - pos[0], start[1] - pos[1]);
                pos[0] = start[0];
                pos[1] = start[1];
                continue;
            }

            int argsPerSegCount = argsPerSegment(cmd);
            double[] args = readArgs(tokens, idx, argsPerSegCount, rel, pos, start, cmd);
            idx += args.length;

            if (cmd == Cmd.S || cmd == Cmd.T) {
                int segLen = cmd == Cmd.S ? 4 : 2;
                for (int segStart = 0; segStart + segLen <= args.length; segStart += segLen) {
                    boolean canReflect = (cmd == Cmd.S && (prevCmd == Cmd.C || prevCmd == Cmd.S))
                            || (cmd == Cmd.T && (prevCmd == Cmd.Q || prevCmd == Cmd.T));
                    double refX = canReflect ? 2 * pos[0] - prevCpX : pos[0];
                    double refY = canReflect ? 2 * pos[1] - prevCpY : pos[1];

                    if (cmd == Cmd.S) {
                        double x1 = rel ? pos[0] + args[segStart] : args[segStart];
                        double y1 = rel ? pos[1] + args[segStart + 1] : args[segStart + 1];
                        double x2 = rel ? pos[0] + args[segStart + 2] : args[segStart + 2];
                        double y2 = rel ? pos[1] + args[segStart + 3] : args[segStart + 3];
                        total += cubicBezierLen(pos[0], pos[1], refX, refY, x1, y1, x2, y2);
                        prevCpX = x1;
                        prevCpY = y1;
                        prevCmd = Cmd.C;
                        pos[0] = x2;
                        pos[1] = y2;
                    } else {
                        double ex = rel ? pos[0] + args[segStart] : args[segStart];
                        double ey = rel ? pos[1] + args[segStart + 1] : args[segStart + 1];
                        total += quadBezierLen(pos[0], pos[1], refX, refY, ex, ey);
                        prevCpX = refX;
                        prevCpY = refY;
                        prevCmd = Cmd.Q;
                        pos[0] = ex;
                        pos[1] = ey;
                    }
                }
                continue;
            }

            if (cmd == Cmd.C) {
                prevCpX = rel ? pos[0] + args[2] : args[2];
                prevCpY = rel ? pos[1] + args[3] : args[3];
                prevCmd = Cmd.C;
            } else if (cmd == Cmd.Q) {
                prevCpX = rel ? pos[0] + args[0] : args[0];
                prevCpY = rel ? pos[1] + args[1] : args[1];
                prevCmd = Cmd.Q;
            } else if (cmd != Cmd.A) {
                prevCmd = cmd;
            }

            double consumed = processSegment(cmd, rel, args, pos, start);
            if (cmd == Cmd.M) {
                int extra = args.length - argsPerSegCount;
                for (int k = argsPerSegCount; k < args.length; k += 2) {
                    total += lineLen(rel, pos, args[k], args[k + 1]);
                }
            } else {
                total += consumed;
            }
        }
        return total;
    }

    private static double processSegment(Cmd cmd, boolean rel, double[] args,
                                         double[] pos, double[] start) {
        double len = 0;
        switch (cmd) {
            case M -> {
                pos[0] = rel ? pos[0] + args[0] : args[0];
                pos[1] = rel ? pos[1] + args[1] : args[1];
                start[0] = pos[0];
                start[1] = pos[1];
            }
            case L -> {
                double ex = rel ? pos[0] + args[0] : args[0];
                double ey = rel ? pos[1] + args[1] : args[1];
                len = Math.hypot(ex - pos[0], ey - pos[1]);
                pos[0] = ex;
                pos[1] = ey;
            }
            case H -> {
                double ex = rel ? pos[0] + args[0] : args[0];
                len = Math.abs(ex - pos[0]);
                pos[0] = ex;
            }
            case V -> {
                double ny = rel ? pos[1] + args[0] : args[0];
                len = Math.abs(ny - pos[1]);
                pos[1] = ny;
            }
            case C -> {
                double x0 = pos[0], y0 = pos[1];
                double x1 = rel ? x0 + args[0] : args[0];
                double y1 = rel ? y0 + args[1] : args[1];
                double x2 = rel ? x0 + args[2] : args[2];
                double y2 = rel ? y0 + args[3] : args[3];
                double x3 = rel ? x0 + args[4] : args[4];
                double y3 = rel ? y0 + args[5] : args[5];
                len = cubicBezierLen(x0, y0, x1, y1, x2, y2, x3, y3);
                pos[0] = x3;
                pos[1] = y3;
            }
            case Q -> {
                double x0 = pos[0], y0 = pos[1];
                double x1 = rel ? x0 + args[0] : args[0];
                double y1 = rel ? y0 + args[1] : args[1];
                double x2 = rel ? x0 + args[2] : args[2];
                double y2 = rel ? y0 + args[3] : args[3];
                len = quadBezierLen(x0, y0, x1, y1, x2, y2);
                pos[0] = x2;
                pos[1] = y2;
            }
            case A -> {
                double x0 = pos[0], y0 = pos[1];
                double rx = Math.abs(args[0]), ry = Math.abs(args[1]);
                double xr = args[2];
                boolean laf = args[3] != 0;
                boolean sf = args[4] != 0;
                double ex = rel ? x0 + args[5] : args[5];
                double ey = rel ? y0 + args[6] : args[6];
                len = arcLen(x0, y0, rx, ry, xr, laf, sf, ex, ey);
                pos[0] = ex;
                pos[1] = ey;
            }
        }
        return len;
    }

    private static double lineLen(boolean rel, double[] pos, double x, double y) {
        double ex = rel ? pos[0] + x : x;
        double ey = rel ? pos[1] + y : y;
        double len = Math.hypot(ex - pos[0], ey - pos[1]);
        pos[0] = ex;
        pos[1] = ey;
        return len;
    }

    // ---- 几何计算 ----

    private static double cubicBezierLen(
            double x0, double y0, double x1, double y1,
            double x2, double y2, double x3, double y3) {
        int STEPS = 20;
        double len = 0, px = x0, py = y0;
        for (int i = 1; i <= STEPS; i++) {
            double t = (double) i / STEPS;
            double mt = 1 - t;
            double x = mt * mt * mt * x0 + 3 * mt * mt * t * x1 + 3 * mt * t * t * x2 + t * t * t * x3;
            double y = mt * mt * mt * y0 + 3 * mt * mt * t * y1 + 3 * mt * t * t * y2 + t * t * t * y3;
            len += Math.hypot(x - px, y - py);
            px = x;
            py = y;
        }
        return len;
    }

    private static double quadBezierLen(
            double x0, double y0, double x1, double y1,
            double x2, double y2) {
        int STEPS = 20;
        double len = 0, px = x0, py = y0;
        for (int i = 1; i <= STEPS; i++) {
            double t = (double) i / STEPS;
            double mt = 1 - t;
            double x = mt * mt * x0 + 2 * mt * t * x1 + t * t * x2;
            double y = mt * mt * y0 + 2 * mt * t * y1 + t * t * y2;
            len += Math.hypot(x - px, y - py);
            px = x;
            py = y;
        }
        return len;
    }

    private static double arcLen(
            double x0, double y0,
            double rx, double ry, double xAxisRot,
            boolean largeArcFlag, boolean sweepFlag,
            double x, double y) {
        if (rx < 0.5 || ry < 0.5) return Math.hypot(x - x0, y - y0);

        int STEPS = 16;
        double len = 0, px = x0, py = y0;
        double cosA = Math.cos(Math.toRadians(xAxisRot));
        double sinA = Math.sin(Math.toRadians(xAxisRot));

        double dx = (x0 - x) / 2, dy = (y0 - y) / 2;
        double x1p = cosA * dx + sinA * dy;
        double y1p = -sinA * dx + cosA * dy;

        double rxSq = rx * rx, rySq = ry * ry;
        double x1pSq = x1p * x1p, y1pSq = y1p * y1p;

        double cr = x1pSq / rxSq + y1pSq / rySq;
        if (cr > 1) {
            double s = Math.sqrt(cr);
            rx *= s;
            ry *= s;
            rxSq = rx * rx;
            rySq = ry * ry;
        }

        double dq = rxSq * y1pSq + rySq * x1pSq;
        if (dq < 1e-10) return Math.hypot(x - x0, y - y0);

        double sq = Math.sqrt(Math.max(0, (rxSq * rySq - dq) / dq));
        if (largeArcFlag == sweepFlag) sq = -sq;

        double cxp = sq * rx * y1p / ry;
        double cyp = -sq * ry * x1p / rx;

        double cx = cosA * cxp - sinA * cyp + (x0 + x) / 2;
        double cy = sinA * cxp + cosA * cyp + (y0 + y) / 2;

        double angle = (ux(x1p - cxp, y1p - cyp, rx, ry) + 2 * Math.PI) % (2 * Math.PI);
        double delta = (ux(-x1p - cxp, -y1p - cyp, rx, ry) - angle + 4 * Math.PI) % (2 * Math.PI);

        if (sweepFlag && delta > 0) delta -= 2 * Math.PI;
        if (!sweepFlag && delta < 0) delta += 2 * Math.PI;

        double deltaStep = delta / STEPS;
        for (int i = 1; i <= STEPS; i++) {
            double t = angle + i * deltaStep;
            double sinT = Math.sin(t), cosT = Math.cos(t);
            double ex = cx + rx * cosT * cosA - ry * sinT * sinA;
            double ey = cy + rx * cosT * sinA + ry * sinT * cosA;
            len += Math.hypot(ex - px, ey - py);
            px = ex;
            py = ey;
        }
        return len;
    }

    private static double ux(double x, double y, double rx, double ry) {
        if (Math.abs(x) < 1e-10 && Math.abs(y) < 1e-10) return 0;
        return Math.atan2(y / ry, x / rx);
    }

    // ================================================================
    // Tokenizer
    // ================================================================

    private static List<Object> tokenize(String d) {
        Matcher matcher = SVG_TOKEN.matcher(d);
        List<Object> tokens = new ArrayList<>();
        while (matcher.find()) {
            String raw = matcher.group();
            if (raw.length() == 1 && "MLCQAZHVSTmlcqazhvst".indexOf(raw.charAt(0)) >= 0) {
                tokens.add(raw.charAt(0));
            } else {
                tokens.add(Double.parseDouble(raw));
            }
        }
        return tokens;
    }

    private static double[] readArgs(List<Object> tokens, int idx,
                                     int count, boolean rel, double[] pos,
                                     double[] start, Cmd cmd) {
        int total = count;
        if (cmd == Cmd.M || cmd == Cmd.L || cmd == Cmd.C || cmd == Cmd.Q || cmd == Cmd.S || cmd == Cmd.T) {
            total = readAllMatching(tokens, idx, count);
        }
        double[] args = new double[total];
        for (int i = 0; i < total; i++) {
            if (idx + i < tokens.size() && tokens.get(idx + i) instanceof Double n) {
                args[i] = n;
            } else {
                args[i] = 0;
            }
        }
        return args;
    }

    private static int readAllMatching(List<Object> tokens, int idx, int coordCount) {
        int max = 0;
        for (int i = idx; i < tokens.size(); i++) {
            if (tokens.get(i) instanceof Character) break;
            max++;
        }
        return (max / coordCount) * coordCount;
    }

    private static int argsPerSegment(Cmd cmd) {
        return switch (cmd) {
            case M, L -> 2;
            case C -> 6;
            case Q -> 4;
            case A -> 7;
            case H, V -> 1;
            case S -> 4;
            case T -> 2;
            case Z -> 0;
        };
    }

    @ThreadSafe
    private enum Cmd {M, L, C, Q, A, Z, H, V, S, T}
}
