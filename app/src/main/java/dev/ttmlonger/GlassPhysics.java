package dev.ttmlonger;

/**
 * Пружина линзы: положение между вкладками, «надувание» при касании и растяжение при движении.
 * Перенесено из функции frame() в index.html без изменений в формулах; шаги не больше 4 мс, чтобы при
 * просадке кадров пружина не «звенела».
 *
 * <p>Положение pos измеряется в вкладках: 0 — первая, 1 — вторая и т. д. (дробное — между ними).
 * Всё считается без Android, поэтому проверяется обычными тестами.
 */
final class GlassPhysics {
    /** Положение линзы и скорость. */
    double pos;
    double vel;
    /** Куда линза едет. */
    double tgt;
    /** Касание: текущее «надувание», его скорость и цель (0 или 1). */
    double press;
    double pvl;
    double pressT;
    /** Растяжение линзы по движению и его скорость. */
    double sx;
    double sxv;
    /** Палец на панели. */
    boolean drag;
    /** Последняя вкладка (для отскока у краёв). */
    int last = 3;

    void reset(int last, double at) {
        this.last = Math.max(0, last);
        pos = tgt = at;
        vel = 0;
        press = pvl = pressT = 0;
        sx = sxv = 0;
        drag = false;
    }

    /** Шаг на dt секунд с текущими настройками. */
    void step(double dtIn) {
        double dt = Math.max(0.0, Math.min(0.05, dtIn));
        if (dt <= 0.0) return;
        double k = drag ? GlassCfg.pk * 0.85 : GlassCfg.pk;
        double cd = drag ? GlassCfg.pd * 0.92 : GlassCfg.pd;
        double jl = GlassCfg.jl / 10.0;
        int n = Math.max(1, (int) Math.ceil(dt / 0.004));
        double h = dt / n;
        for (int i = 0; i < n; i++) {
            vel += (k * (tgt - pos) - cd * vel) * h;
            pos += vel * h;
            pvl += (300.0 * (pressT - press) - 19.0 * pvl) * h;
            press += pvl * h;
            double want = Math.max(-0.3, Math.min(0.3, vel * 0.022 * jl));
            sxv += (320.0 * (want - sx) - 17.0 * sxv) * h;
            sx += sxv * h;
            if (pos < 0 || pos > last) {
                double e = pos < 0 ? 0 : last;
                pos = e + (pos - e) * 0.3;
                vel *= 0.6;
            }
        }
    }

    /** Нужен ли ещё кадр: что-то движется или не дошло до цели. */
    boolean active() {
        return drag || Math.abs(tgt - pos) > 0.001 || Math.abs(vel) > 0.01 || Math.abs(press - pressT) > 0.002
                || Math.abs(pvl) > 0.01 || Math.abs(sx) > 0.002 || Math.abs(sxv) > 0.02;
    }

    /** Палец лёг на панель в положении at (в вкладках). */
    void down(double at) {
        drag = true;
        pressT = 1;
        tgt = clampPos(at);
    }

    void move(double at) {
        if (drag) tgt = clampPos(at);
    }

    /** Палец поднят: линза доезжает до ближайшей вкладки. Возвращает её номер. */
    int up(double at) {
        drag = false;
        pressT = 0;
        int n = (int) Math.round(clampPos(at));
        tgt = n;
        return n;
    }

    double clampPos(double v) {
        return Math.max(0, Math.min(last, v));
    }
}
