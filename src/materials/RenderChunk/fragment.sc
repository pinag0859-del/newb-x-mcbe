$input v_color0, v_color1, v_fog, v_refl, v_texcoord0, v_lightmapUV, v_extra

#include <bgfx_shader.sh>
#include <newb/main.sh>

// Sombras en capas (0.0 = apagada, 1.0 = fuerte)
#ifndef SHADOW_SOFT
  #define SHADOW_SOFT 0.85      // capa clara (sombra suave, da profundidad)
#endif
#ifndef SHADOW_DEEP
  #define SHADOW_DEEP 0.90      // capa oscura (zonas muy oscuras)
#endif

// Donde empiezan las sombras (valores de luz de cielo, de 0.0 a ~0.9)
// Si la sombra clara empieza antes (LO mas cerca de HI) y sube de golpe, se ve mas marcada
#ifndef SOFT_HI
  #define SOFT_HI 0.88          // por encima de esto no hay sombra
#endif
#ifndef SOFT_LO
  #define SOFT_LO 0.66          // por debajo de esto la sombra clara es total
#endif
#ifndef DEEP_HI
  #define DEEP_HI 0.62          // por encima de esto no hay capa oscura
#endif
#ifndef DEEP_LO
  #define DEEP_LO 0.30          // por debajo de esto la capa oscura es total
#endif

// MODO PRUEBA: pinta el terreno con colores segun la luz de cielo (1 = activado, 0 = apagado)
#ifndef DEBUG_SKY
  #define DEBUG_SKY 1
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
    float sky = v_extra.r;
    float lit  = smoothstep(SOFT_LO, SOFT_HI, sky);    // 1 = al sol
    float deep = 1.0 - smoothstep(DEEP_LO, DEEP_HI, sky); // 1 = muy oscuro
    float soft = (1.0 - lit) * (1.0 - deep);           // zona intermedia

    // luz de antorchas: debilita las sombras cerca de ellas
    float torch = clamp(v_lightmapUV.x * 1.6 - 0.2, 0.0, 1.0);
    float keep = 1.0 - torch;

    // capa clara azulada y capa oscura casi negra
    vec3 softCol = diffuse.rgb * vec3(0.62, 0.68, 0.88);
    vec3 deepCol = diffuse.rgb * vec3(0.28, 0.30, 0.38);

    diffuse.rgb = mix(diffuse.rgb, softCol, soft * SHADOW_SOFT * keep);
    diffuse.rgb = mix(diffuse.rgb, deepCol, deep * SHADOW_DEEP * keep);

    // luz de sol ligeramente cálida en zonas iluminadas
    diffuse.rgb *= mix(vec3_splat(1.0), vec3(1.06, 1.02, 0.94), lit);
  }
  // ===== FIN SOMBRAS EN CAPAS =====

  diffuse.rgb += glow;

  if (v_extra.b > 0.9) {
    diffuse.rgb += v_refl.rgb*v_refl.a;
  } else if (v_refl.a > 0.0) {
    // reflective effect - only on xz plane
    float dy = abs(dFdy(v_extra.g));
    if (dy < 0.0002) {
      float mask = v_refl.a*(clamp(v_extra.r*10.0,8.2,8.8)-7.8);
      diffuse.rgb *= 1.0 - 0.6*mask;
      diffuse.rgb += v_refl.rgb*mask;
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
