$input v_color0, v_color1, v_fog, v_refl, v_texcoord0, v_lightmapUV, v_extra

#include <bgfx_shader.sh>
#include <newb/main.sh>

// Sombras en capas (0.0 = apagada, 1.0 = fuerte)
#ifndef SHADOW_SOFT
  #define SHADOW_SOFT 1.00      // capa clara (sombra suave, da profundidad)
#endif
#ifndef SHADOW_DEEP
  #define SHADOW_DEEP 1.00      // capa oscura (zonas muy oscuras)
#endif

// Donde empiezan las sombras (valores de luz de cielo, de 0.0 a ~0.9)
// Si la sombra clara empieza antes (LO mas cerca de HI) y sube de golpe, se ve mas marcada
#ifndef SOFT_HI
  #define SOFT_HI 0.92          // por encima de esto no hay sombra (cielo abierto)
#endif
#ifndef SOFT_LO
  #define SOFT_LO 0.86          // por debajo de esto la sombra clara es total
#endif
#ifndef DEEP_HI
  #define DEEP_HI 0.78          // por encima de esto no hay capa oscura
#endif
#ifndef DEEP_LO
  #define DEEP_LO 0.40          // por debajo de esto la capa oscura es total
#endif

// MODO PRUEBA: pinta el terreno con colores segun la luz de cielo (1 = activado, 0 = apagado)
#ifndef DEBUG_SKY
  #define DEBUG_SKY 0
#endif

// Sombras en bordes y esquinas de bloques (0.0 = apagadas, 1.0 = fuertes)
#ifndef EDGE_STRENGTH
  #define EDGE_STRENGTH 1.0
#endif
#ifndef EDGE_SIDE_STRENGTH
  #define EDGE_SIDE_STRENGTH 0.25  // fuerza en paredes (0.0 = paredes sin tocar)
#endif
#ifndef EDGE_MIN
  #define EDGE_MIN 0.30         // lo mas oscuro que llegan los bordes (0.0 = negro, 1.0 = sin efecto)
#endif
#ifndef EDGE_START
  #define EDGE_START 0.45       // luz a partir de la cual el anillo es lo mas oscuro
#endif
#ifndef EDGE_END
  #define EDGE_END 0.93         // por encima de esto no hay sombra de borde
#endif

// Contorno oscuro en los bordes de cada bloque (1 = activado, 0 = apagado y sin costo)
#ifndef ENABLE_OUTLINE
  #define ENABLE_OUTLINE 0
#endif
#ifndef OUTLINE_STRENGTH
  #define OUTLINE_STRENGTH 1.0   // 0.0 = apagado
#endif
#ifndef OUTLINE_DARK
  #define OUTLINE_DARK 0.55      // 0.0 = negro, 1.0 = sin efecto
#endif
#ifndef OUTLINE_TEXELS
  #define OUTLINE_TEXELS 1.0     // grosor en pixeles de textura (minimo 1.0)
#endif
#ifndef ATLAS_TILES
  #define ATLAS_TILES vec2(64.0, 32.0)  // bloques de 16 px en un atlas de 1024x512
#endif

// Punto de brillo del sol en el suelo (necesita NL_GROUND_REFL activado en config.h)
#ifndef DOT_GAIN
  #define DOT_GAIN 8.0          // intensidad del punto. Si se ve debil, sube a 20.0
#endif
#ifndef DOT_POWER
  #define DOT_POWER 3.0         // tamano: mas alto = punto mas pequeno y concentrado (2.0 a 5.0)
#endif

// Saturacion del mundo (1.0 = igual que antes, mas de 1.0 = colores mas vivos)
#ifndef WORLD_SATURATION
  #define WORLD_SATURATION 1.35
#endif

SAMPLER2D_AUTOREG(s_MatTexture);
SAMPLER2D_AUTOREG(s_SeasonsTexture);
SAMPLER2D_AUTOREG(s_LightMapTexture);

void main() {
  #if defined(DEPTH_ONLY_OPAQUE) || defined(DEPTH_ONLY) || defined(INSTANCING)
    gl_FragColor = vec4(1.0,1.0,1.0,1.0);
    return;
  #endif

  vec4 diffuse = texture2D(s_MatTexture, v_texcoord0);
  vec4 color = v_color0;
  float heightDy = abs(dFdy(v_extra.g));   // casi 0 en caras horizontales (suelo)

  #ifdef ALPHA_TEST
    if (diffuse.a < 0.6) {
      discard;
    }
  #endif

  #if defined(SEASONS) && (defined(OPAQUE) || defined(ALPHA_TEST))
    diffuse.rgb *= mix(vec3(1.0,1.0,1.0), texture2D(s_SeasonsTexture, v_color1.xy).rgb * 2.0, v_color1.z);
  #endif

  vec3 glow = nlGlow(s_MatTexture, v_texcoord0, v_extra.a);

  diffuse.rgb *= diffuse.rgb;

  #if defined(TRANSPARENT) && !(defined(SEASONS) || defined(RENDER_AS_BILLBOARDS))
    if (v_extra.b > 0.9) {
      diffuse.rgb = vec3_splat(1.0 - NL_WATER_TEX_OPACITY*(1.0 - diffuse.b*1.8));
      diffuse.a = color.a;
    }
  #else
    diffuse.a = 1.0;
  #endif

  diffuse.rgb *= color.rgb;

  // ===== SOMBRAS EN CAPAS (añadido) =====
  // Se aplican antes del brillo emisivo para que antorchas y lava no se oscurezcan.
  if (v_extra.b < 0.9) {
    // luz de cielo: ~0.9 al aire libre, baja bajo techos, árboles y en cuevas
    float sky = v_lightmapUV.y;
    float lit  = smoothstep(SOFT_LO, SOFT_HI, sky);    // 1 = al sol
    float deep = 1.0 - smoothstep(DEEP_LO, DEEP_HI, sky); // 1 = muy oscuro
    float soft = (1.0 - lit) * (1.0 - deep);           // zona intermedia

    // luz de antorchas: debilita las sombras cerca de ellas
    float torch = clamp(v_lightmapUV.x * 1.6 - 0.2, 0.0, 1.0);
    float keep = 1.0 - torch;

    // capa clara azulada y capa oscura casi negra
    vec3 softCol = diffuse.rgb * vec3(0.58, 0.64, 0.74);
    vec3 deepCol = diffuse.rgb * vec3(0.20, 0.22, 0.30);

    diffuse.rgb = mix(diffuse.rgb, softCol, soft * SHADOW_SOFT * keep);
    diffuse.rgb = mix(diffuse.rgb, deepCol, deep * SHADOW_DEEP * keep);

    // bordes y esquinas: v_extra.r trae el sombreado de caras y esquinas
    // anillos de sombra como en la prueba de colores: en el suelo se aplican completos,
    // en las paredes solo un poco para no oscurecerlas
    bool isTop = heightDy < 0.0002;
    float eo = smoothstep(EDGE_START, EDGE_END, v_extra.r);
    float edge = mix(EDGE_MIN, 1.0, eo);
    float ringStrength = isTop ? EDGE_STRENGTH : EDGE_SIDE_STRENGTH;
    diffuse.rgb *= mix(1.0, edge, ringStrength);

    // luz de sol ligeramente cálida en zonas iluminadas
    diffuse.rgb *= mix(vec3_splat(1.0), vec3(1.06, 1.02, 0.94), lit);
  }
  // ===== FIN SOMBRAS EN CAPAS =====

  // ===== CONTORNO DE BLOQUES (añadido) =====
  // Oscurece los pixeles del borde de cada cara. No se aplica a plantas, hojas ni agua.
  #if ENABLE_OUTLINE && !defined(ALPHA_TEST) && !defined(TRANSPARENT)
  {
    vec2 f = fract(v_texcoord0 * ATLAS_TILES);   // posicion dentro del bloque (0 a 1)
    vec2 tx = floor(f * 16.0);                   // pixel de textura (0 a 15)
    vec2 d = min(tx, vec2(15.0, 15.0) - tx);     // distancia al borde en pixeles
    float de = min(d.x, d.y);                    // 0 = justo en el borde
    float line = 1.0 - smoothstep(0.0, OUTLINE_TEXELS, de);
    diffuse.rgb *= mix(1.0, OUTLINE_DARK, line * OUTLINE_STRENGTH);
  }
  #endif
  // ===== FIN CONTORNO =====

  diffuse.rgb += glow;

  if (v_extra.b > 0.9) {
    diffuse.rgb += v_refl.rgb*v_refl.a;
  } else if (v_refl.a > 0.0) {
    // reflective effect - only on xz plane
    float dy = abs(dFdy(v_extra.g));
    if (dy < 0.0002) {
      float mask = v_refl.a*(clamp(v_extra.r*10.0,8.2,8.8)-7.8);
      // solo el punto: se ignora el reflejo suave del cielo y se deja la parte mas luminosa
      float glowLum = dot(v_refl.rgb, vec3(0.333, 0.333, 0.333));
      float hot = pow(max(glowLum, 0.0), DOT_POWER);
      diffuse.rgb += v_refl.rgb*mask*hot*DOT_GAIN;
    }
  }

  diffuse.rgb = mix(diffuse.rgb, v_fog.rgb, v_fog.a);

  diffuse.rgb = colorCorrection(diffuse.rgb);

  // ===== SATURACION (añadido) =====
  float gris = dot(diffuse.rgb, vec3(0.299, 0.587, 0.114));
  diffuse.rgb = max(mix(vec3_splat(gris), diffuse.rgb, WORLD_SATURATION), vec3_splat(0.0));

#if DEBUG_SKY
  {
    // mitad izquierda de la pantalla = v_extra.r, mitad derecha = v_lightmapUV.y
    float s = (gl_FragCoord.x < 800.0) ? v_extra.r : v_lightmapUV.y;
    vec3 dbg = vec3(0.0, 0.0, 0.0);              // negro      : menos de 0.30
    if (s > 0.30) dbg = vec3(0.5, 0.0, 0.0);     // rojo oscuro: 0.30 a 0.45
    if (s > 0.45) dbg = vec3(1.0, 0.0, 0.0);     // rojo       : 0.45 a 0.60
    if (s > 0.60) dbg = vec3(1.0, 0.5, 0.0);     // naranja    : 0.60 a 0.70
    if (s > 0.70) dbg = vec3(1.0, 1.0, 0.0);     // amarillo   : 0.70 a 0.78
    if (s > 0.78) dbg = vec3(0.0, 1.0, 0.0);     // verde      : 0.78 a 0.84
    if (s > 0.84) dbg = vec3(0.0, 1.0, 1.0);     // celeste    : 0.84 a 0.88
    if (s > 0.88) dbg = vec3(0.0, 0.0, 1.0);     // azul       : 0.88 a 0.92
    if (s > 0.92) dbg = vec3(1.0, 1.0, 1.0);     // blanco     : mas de 0.92
    diffuse.rgb = dbg;
  }
#endif

  gl_FragColor = diffuse;
}
