package dev.ttmlonger;

/**
 * Шейдер стеклянной линзы на AGSL (Android 13+). Это тот же фрагментный шейдер FS из index.html:
 * скруглённая SDF-линза, преломление (n = 1,5), хроматическая аберрация у кромки, блик Френеля по краю,
 * тёмная «таблетка» выбранной вкладки в покое и тонкое тёмное кольцо. Отличия только в записи:
 * vec → float, texture2D(T, p / R) → T.eval(p) (AGSL работает в пикселях), gl_FragCoord уже сверху вниз.
 * Добавлены SAT и BRT (насыщенность и яркость линзы из настроек; в index.html они лежали в CSS) и BG: цвет
 * подложки капсулы, которым закрывается линза, чтобы родные значки TikTok не просвечивали сквозь преломлённые.
 *
 * <p>Текстура T — это снимок значков и подписей панели (без фона), как canvas t2 в index.html.
 *
 * <p>Поверх index.html добавлено то, что видно на видео-образце настоящей панели: светлая матовая «таблетка» с
 * размытием под ней при движении (V), сжатие панели до значков (SC, CY, YC, LT, CX, EZ, LA, CA: значки уменьшаются
 * к центру, подписи гаснут, «+» остаётся на месте), увеличение под линзой (ZM) и раздвигание соседних значков (PS).
 */
final class GlassShader {
    private GlassShader() {
    }

    /** Имена uniform-переменных (все они объявлены и используются в шейдере). */
    static final String[] UNIFORMS = {"R", "N", "B", "L", "S", "P", "Q", "W", "Y", "K", "Z", "D", "I", "SAT", "BRT", "BG", "ZM", "PS", "GA", "V", "SC", "CY", "YC", "LT", "CX", "EZ", "LA", "CA"};

    static final String SRC = ""
            + "uniform shader T;\n"
            + "uniform float2 R;\n"
            + "uniform float2 N;\n"
            + "uniform float2 B;\n"
            + "uniform float4 L;\n"
            + "uniform float S;\n"
            + "uniform float P;\n"
            + "uniform float Q;\n"
            + "uniform float W;\n"
            + "uniform float Y;\n"
            + "uniform float K;\n"
            + "uniform float Z;\n"
            + "uniform float D;\n"
            + "uniform float I;\n"
            + "uniform float SAT;\n"
            + "uniform float BRT;\n"
            + "uniform float3 BG;\n"
            + "uniform float ZM;\n"
            + "uniform float PS;\n"
            + "uniform float GA;\n"
            + "uniform float V;\n"
            + "uniform float SC;\n"
            + "uniform float CY;\n"
            + "uniform float YC;\n"
            + "uniform float LT;\n"
            + "uniform float CX;\n"
            + "uniform float EZ;\n"
            + "uniform float LA;\n"
            + "uniform float CA;\n"
            + "\n"
            + "float sd(float2 p, float2 h, float r) {\n"
            + "  float2 q = abs(p) - h + float2(r);\n"
            + "  return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;\n"
            + "}\n"
            + "float2 gr(float2 p, float2 h, float r) {\n"
            + "  float2 e = float2(1.5, 0.0);\n"
            + "  float2 g = float2(sd(p + e.xy, h, r) - sd(p - e.xy, h, r), sd(p + e.yx, h, r) - sd(p - e.yx, h, r));\n"
            + "  return normalize(g + float2(0.00001));\n"
            + "}\n"
            + "float hp(float x) {\n"
            + "  return pow(1.0 - pow(1.0 - x, 4.0), 0.25);\n"
            + "}\n"
            + "float rgf(float d, float w) {\n"
            + "  return smoothstep(-w * 2.6, -w * 0.6, d) * (1.0 - smoothstep(-w * 0.6, w * 0.4, d));\n"
            + "}\n"
            // сжатие панели: точка на экране → точка в снимке значков; зона «+» (EZ) остаётся как есть
            + "float2 cm(float2 q) {\n"
            + "  float dx = q.x - CX;\n"
            + "  float ax = abs(dx);\n"
            + "  if (SC >= 0.999 || ax <= EZ) return q;\n"
            + "  float sa = EZ + (ax - EZ) / SC;\n"
            + "  return float2(CX + (dx >= 0.0 ? sa : -sa), YC + (q.y - YC) / SC);\n"
            + "}\n"
            // подписи гаснут при сжатии
            + "float lf(float2 q) {\n"
            + "  if (SC >= 0.999 || abs(q.x - CX) <= EZ) return 1.0;\n"
            + "  float sy = YC + (q.y - YC) / SC;\n"
            + "  return sy > LT ? LA : 1.0;\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 fc) {\n"
            + "  float2 p = fc;\n"
            + "  float2 bp = p - float2(R.x * 0.5, CY);\n"
            // свет «привязан» к сцене: блики скользят по кромке, когда линза едет
            + "  float2 Ld = normalize(float2(-0.55 + clamp((L.x - R.x * 0.5) / R.x, -0.5, 0.5) * 1.1, -0.83));\n"
            + "  float bd = sd(bp, N * 0.5, N.y * 0.5);\n"
            + "  float li = dot(gr(bp, N * 0.5, N.y * 0.5), Ld);\n"
            + "  float2 lp = p - L.xy;\n"
            + "  float r = L.w;\n"
            + "  float d = sd(lp, L.zw, r);\n"
            + "  float m = (1.0 - smoothstep(-1.2, 0.8, d)) * (1.0 - Z);\n"
            + "  float pm = 0.0;\n"
            + "  float pd = 0.0;\n"
            + "  if (K > 0.0) {\n"
            + "    pd = sd(p - float2(W, CY), B, B.y);\n"
            + "    pm = 1.0 - smoothstep(-1.0, 0.7, pd);\n"
            + "  }\n"
            // под пальцем линза раздвигает соседние значки: источник сдвинут к линзе, значит значок уезжает от неё
            + "  float pa = GA * max(smoothstep(0.0, 0.03, P), CA);\n"
            + "  float dxl = p.x - L.x;\n"
            + "  float edgeD = max(abs(dxl) - L.z, 0.0);\n"
            + "  float sg = dxl >= 0.0 ? 1.0 : -1.0;\n"
            + "  float push = PS * P * (1.0 - Z) * exp(-edgeD / (N.y * 1.2));\n"
            + "  float2 sp = cm(p) - float2(sg * push, 0.0);\n"
            + "  float lab = lf(p);\n"
            + "  float4 col = float4(T.eval(sp)) * lab;\n"
            // под движущейся «таблеткой» всё слегка размыто по ходу движения
            + "  if (V > 0.5 && pm > 0.0) {\n"
            + "    float4 b1 = float4(T.eval(sp + float2(V, 0.0))) * lab;\n"
            + "    float4 b2 = float4(T.eval(sp - float2(V, 0.0))) * lab;\n"
            + "    float4 b3 = float4(T.eval(sp + float2(V * 0.5, 0.0))) * lab;\n"
            + "    float4 b4 = float4(T.eval(sp - float2(V * 0.5, 0.0))) * lab;\n"
            + "    col = mix(col, (col * 2.0 + b1 + b2 + b3 + b4) / 6.0, pm);\n"
            + "  }\n"
            // значки перерисовываются там, где под ними «таблетка» или линза, а при касании и сжатии по всей капсуле
            + "  float keep = max(max(pm, m), pa);\n"
            + "  col *= keep;\n"
            // кромка капсулы: блик сверху-слева и слабый снизу-справа
            + "  col += float4(1.0) * (smoothstep(-N.y * 0.07, 0.0, bd) * (0.5 * max(li, 0.0) + 0.16 * max(-li, 0.0)));\n"
            + "  col *= 1.0 - smoothstep(-1.0, 0.5, bd);\n"
            // iOS: в покое выбранная вкладка лежит на светлой матовой «таблетке» (на светлой панели — на тёмной)
            + "  if (K > 0.0) {\n"
            + "    float pf = K * pm * (0.10 + 0.12 * D);\n"
            + "    col += float4(float3(D * pf), pf) * (1.0 - col.a);\n"
            + "    float pe = K * smoothstep(-1.8, -0.3, pd) * (1.0 - smoothstep(-0.3, 0.9, pd)) * (0.10 + 0.10 * D);\n"
            + "    col += float4(float3(D * pe), pe) * (1.0 - col.a);\n"
            + "  }\n"
            // при касании родные значки закрыты подложкой капсулы: вместо них видны смещённые копии
            + "  col += float4(BG, 1.0) * (pa * (1.0 - smoothstep(-1.0, 0.5, bd)) * (1.0 - col.a));\n"
            + "  if (m > 0.0) {\n"
            + "    float2 n = gr(lp, L.zw, r);\n"
            + "    float bw = r * 0.26;\n"
            + "    float x = clamp(-d / bw, 0.0, 1.0);\n"
            + "    float e = 0.02;\n"
            + "    float x1 = min(x + e, 1.0);\n"
            + "    float x0 = max(x - e, 0.0);\n"
            // выпуклая линза: профиль → наклон поверхности → луч преломления (n = 1,5)
            + "    float H = r * 0.55 * S * (1.0 + 0.35 * P);\n"
            + "    float sl = min((hp(x1) - hp(x0)) / (x1 - x0) * H / bw, 2.0);\n"
            + "    float3 nr = normalize(float3(n * sl, 1.0));\n"
            + "    float3 rf = refract(float3(0.0, 0.0, -1.0), nr, 1.0 / 1.5);\n"
            // под линзой всё крупнее: преломление плюс увеличение ZM, как в видео-образце
            + "    float2 base = L.xy + lp * (1.0 - (0.04 + 0.07 * P) * S) / (1.0 + ZM * P);\n"
            + "    float2 sc = base + rf.xy / (-rf.z) * H * 0.4;\n"
            // хроматическая аберрация только у кромки: R наружу, B внутрь
            + "    float2 ab = n * pow(1.0 - x, 2.5) * N.y * 0.035 * Y;\n"
            + "    float lq = lf(sc);\n"
            + "    float4 g = float4(T.eval(cm(sc))) * lq;\n"
            + "    float4 ga = float4(T.eval(cm(sc + ab))) * lq;\n"
            + "    float4 gb = float4(T.eval(cm(sc - ab))) * lq;\n"
            + "    g.r = ga.r;\n"
            + "    g.b = gb.b;\n"
            + "    g.a = max(g.a, max(ga.a, gb.a));\n"
            + "    float lum = dot(g.rgb, float3(0.299, 0.587, 0.114));\n"
            + "    g.rgb = mix(float3(lum), g.rgb, SAT);\n"
            + "    float lam = dot(n, Ld);\n"
            + "    float ed = 1.0 - x;\n"
            + "    float fr = pow(1.0 - clamp(nr.z, 0.0, 1.0), 1.6);\n"
            + "    float body = (0.05 - 0.03 * P) * (0.6 + 0.4 * clamp(0.5 - lp.y / (L.w * 2.0), 0.0, 1.0)) * BRT;\n"
            + "    g += float4(float3(body), body) * (1.0 - g.a);\n"
            // затемнение под кромкой даёт объём
            + "    float sh = pow(ed, 3.0) * 0.2 * (0.5 + 0.5 * max(-lam, 0.0));\n"
            + "    g.rgb *= 1.0 - sh;\n"
            + "    g.a = max(g.a, sh * 0.9);\n"
            + "    float sheen = pow(clamp(-lp.y / L.w, 0.0, 1.0), 2.0) * x * 0.05;\n"
            + "    float rw = N.y * 0.016;\n"
            + "    float spec = (1.2 * pow(max(lam, 0.0), 2.4) + 0.55 * pow(max(-lam, 0.0), 2.4) + 0.06)"
            + " * (0.55 + 0.9 * fr) * Q;\n"
            + "    float hl = rgf(d, rw) * spec"
            + " + smoothstep(-bw * 0.7, 0.0, d) * (1.0 - smoothstep(-rw * 0.3, rw * 0.4, d))"
            + " * (0.14 * max(lam, 0.0) + 0.07 * max(-lam, 0.0)) * (0.6 + fr) * Q + sheen * Q;\n"
            // тонкая цветная кромка блика
            + "    float h0 = rgf(d, rw) * spec;\n"
            + "    float dd = N.y * 0.006 * Y;\n"
            + "    g.rgb += float3(rgf(d - dd, rw) * spec - h0, 0.0, rgf(d + dd, rw) * spec - h0) * 0.75;\n"
            + "    g.rgb += float3(hl) * mix(float3(0.9, 0.96, 1.1), float3(1.1, 0.95, 0.88),"
            + " smoothstep(-rw * 1.2, rw * 0.6, d));\n"
            + "    g.a = max(g.a, hl);\n"
            // под линзой родные значки TikTok не должны просвечивать: подкладываем цвет подложки капсулы
            + "    float inCap = 1.0 - smoothstep(-1.0, 0.5, bd);\n"
            + "    g += float4(BG, 1.0) * (inCap * (1.0 - g.a));\n"
            + "    col = mix(col, g, m);\n"
            + "  }\n"
            // тонкое тёмное кольцо по кромке линзы, как в iOS
            + "  float rd = I * (1.0 - Z) * (1.0 - smoothstep(0.2, 1.6, abs(d - 0.5))) * (0.20 + 0.14 * P);\n"
            + "  col.rgb *= 1.0 - rd;\n"
            + "  col.a = max(col.a, rd);\n"
            + "  return half4(clamp(col, 0.0, 1.0));\n"
            + "}\n";
}
