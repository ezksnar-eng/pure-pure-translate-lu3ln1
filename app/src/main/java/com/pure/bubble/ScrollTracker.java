package com.pure.bubble;

/**
 * يقدّر حركة الشاشة بين لقطتين (تمرير عمودي) ويتجاهل مناطق صناديق الترجمة.
 *
 * التمثيل: أعمدة متباعدة (كل colStep بكسل) × كل صفوف الشاشة، بدرجات الرمادي.
 * الفهرس = c * h + y.
 *
 * الاتجاه: dy موجب = المحتوى نزل لتحت (صف y بالسابقة يصير y+dy بالحالية).
 * لما المستخدم يسحب الصفحة لفوق يطلع dy سالب.
 */
public final class ScrollTracker {
    public static final int STATIC = 0;   // ما تغيّر شي
    public static final int SCROLL = 1;   // تمرير عمودي بمقدار dy
    public static final int PARTIAL = 2;  // تغيّر جزئي (نص جديد ظهر...)
    public static final int PAGE = 3;     // تغيّر كامل (صفحة جديدة) أو تمرير أسرع من اللي نقدر نتتبعه

    public static final class Result {
        public final int kind;
        public final int dy;
        public final float changed;
        /** حدود التغيّر بالبكسل [l,t,r,b] أو null. */
        public final int[] dirty;

        Result(int kind, int dy, float changed, int[] dirty) {
            this.kind = kind;
            this.dy = dy;
            this.changed = changed;
            this.dirty = dirty;
        }
    }

    public final int cols;
    public final int h;
    public final int colStep;
    private final int maxShift;
    private double bestScore;

    public ScrollTracker(int width, int height, int colStep) {
        this.colStep = colStep;
        this.h = height;
        this.cols = Math.max(1, width / colStep);
        this.maxShift = Math.min(700, height * 2 / 5);
    }

    public int colX(int c) {
        return c * colStep + colStep / 2;
    }

    public int[] newGray() {
        return new int[cols * h];
    }

    public boolean[] newMask() {
        return new boolean[cols * h];
    }

    /** يعلّم مستطيل (بالبكسل) كمنطقة مغطاة بالصناديق. */
    public void markRect(boolean[] mask, int left, int top, int right, int bottom) {
        int y0 = Math.max(0, top);
        int y1 = Math.min(h, bottom);
        if (y1 <= y0) return;
        for (int c = 0; c < cols; c++) {
            int x = colX(c);
            if (x < left || x >= right) continue;
            int base = c * h;
            for (int y = y0; y < y1; y++) mask[base + y] = true;
        }
    }

    private double score(int[] prev, boolean[] pm, int[] cur, boolean[] cm,
                         int dy, int rowStep, int colSkip) {
        int y0 = Math.max(0, -dy);
        int y1 = Math.min(h, h - dy);
        if (y1 - y0 < h / 3) return 1e9;
        long sum = 0;
        int n = 0;
        for (int c = 0; c < cols; c += colSkip) {
            int base = c * h;
            for (int y = y0; y < y1; y += rowStep) {
                int ip = base + y;
                int ic = base + y + dy;
                if (pm[ip] || cm[ic]) continue;
                sum += Math.abs(prev[ip] - cur[ic]);
                n++;
            }
        }
        if (n < 300) return 1e9;
        return sum / (double) n;
    }

    /** بحث خشن: يرجع أفضل إزاحة بالمدى [lo,hi]. التعادل يفضّل الأقرب للصفر. */
    private int coarse(int[] prev, boolean[] pm, int[] cur, boolean[] cm, int lo, int hi, int step) {
        int best = 0;
        double bs = Double.MAX_VALUE;
        for (int d = lo; d <= hi; d += step) {
            double s = score(prev, pm, cur, cm, d, 6, 3);
            if (s < bs - 1e-9 || (Math.abs(s - bs) <= 1e-9 && Math.abs(d) < Math.abs(best))) {
                bs = s;
                best = d;
            }
        }
        bestScore = bs;
        return best;
    }

    private int refine(int[] prev, boolean[] pm, int[] cur, boolean[] cm, int center, int radius) {
        int fd = center;
        double fb = 1e18;
        for (int d = center - radius; d <= center + radius; d++) {
            double s = score(prev, pm, cur, cm, d, 2, 1);
            if (s < fb) {
                fb = s;
                fd = d;
            }
        }
        bestScore = fb;
        return fd;
    }

    private float diffFraction(int[] prev, boolean[] pm, int[] cur, boolean[] cm, int[] bounds) {
        int n = 0;
        int diff = 0;
        int minC = Integer.MAX_VALUE, maxC = -1, minY = Integer.MAX_VALUE, maxY = -1;
        for (int c = 0; c < cols; c++) {
            int base = c * h;
            for (int y = 0; y < h; y += 2) {
                int i = base + y;
                if (pm[i] || cm[i]) continue;
                n++;
                if (Math.abs(prev[i] - cur[i]) > 24) {
                    diff++;
                    if (c < minC) minC = c;
                    if (c > maxC) maxC = c;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (n < 400) return -1f;
        if (bounds != null && maxC >= 0) {
            bounds[0] = minC * colStep;
            bounds[1] = minY;
            bounds[2] = (maxC + 1) * colStep;
            bounds[3] = maxY + 2;
        }
        return diff / (float) n;
    }

    /**
     * @param hint آخر إزاحة معروفة (سرعة التمرير)، 0 لو ما في.
     */
    public Result compare(int[] prev, boolean[] pm, int[] cur, boolean[] cm, int hint) {
        int[] bounds = new int[]{0, 0, 0, 0};
        float frac0 = diffFraction(prev, pm, cur, cm, bounds);
        if (frac0 < 0f) return new Result(STATIC, 0, 0f, null);
        if (frac0 < 0.012f) return new Result(STATIC, 0, frac0, null);

        double s0 = score(prev, pm, cur, cm, 0, 2, 1);

        // 1) بحث محلي حول السرعة السابقة (أسرع بكثير)
        if (hint != 0) {
            int lo = Math.max(-maxShift, hint - 24);
            int hi = Math.min(maxShift, hint + 24);
            int c1 = coarse(prev, pm, cur, cm, lo, hi, 1);
            int fd = refine(prev, pm, cur, cm, c1, 2);
            if (fd != 0 && bestScore < 14.0 && bestScore < 0.5 * s0) {
                return new Result(SCROLL, fd, frac0, null);
            }
        }

        // 2) بحث كامل
        int c = coarse(prev, pm, cur, cm, -maxShift, maxShift, 2);
        if (c != 0) {
            int fd = refine(prev, pm, cur, cm, c, 3);
            if (fd != 0 && bestScore < 14.0 && bestScore < 0.5 * s0) {
                return new Result(SCROLL, fd, frac0, null);
            }
        }
        if (frac0 < 0.2f) return new Result(PARTIAL, 0, frac0, bounds);
        return new Result(PAGE, 0, frac0, null);
    }

    /**
     * استعادة التتبع بعد تمرير سريع: بحث أوسع (ما يطلب سرعة سابقة).
     * يرجع SCROLL مع الإزاحة الكلية، أو PAGE لو ما لقى تطابق.
     */
    public Result compareWide(int[] prev, boolean[] pm, int[] cur, boolean[] cm) {
        int wide = h * 2 / 3 - 8;
        int c = coarse(prev, pm, cur, cm, -wide, wide, 2);
        int fd = refine(prev, pm, cur, cm, c, 3);
        double s0 = score(prev, pm, cur, cm, 0, 2, 1);
        if (bestScore < 14.0 && (fd == 0 || bestScore < 0.5 * s0)) {
            if (fd == 0) return new Result(STATIC, 0, 0f, null);
            return new Result(SCROLL, fd, 1f, null);
        }
        return new Result(PAGE, 0, 1f, null);
    }
}
