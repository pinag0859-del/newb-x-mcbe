$input v_color0, v_color1, v_fog, v_refl, v_texcoord0, v_lightmapUV, v_extra

#include <bgfx_shader.sh>
#include <newb/main.sh>

// Fuerza de las sombras falsas (0.0 = apagadas, 1.0 = fuertes)
#ifndef FAKE_SHADOW_STRENGTH
  #define FAKE_SHADOW_STRENGTH 0.9
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

  // ===== SOMBRAS FALSAS (añadido) =====
  // Se aplican antes del brillo emisivo para que antorchas y lava no se oscurezcan.
  if (v_extra.b < 0.9) {
    // luz de cielo: ~0.9 al aire libre, baja bajo techos, árboles y en cuevas
    float sky = v_extra.r;
    float lit = smoothstep(0.55, 0.88, sky);            // 1 = al sol, 0 = en sombra

    // luz de antorchas: debilita la sombra cerca de ellas
    float torch = clamp(v_lightmapUV.x * 1.6 - 0.2, 0.0, 1.0);

    float shadow = clamp((1.0 - lit) * (1.0 - torch) * FAKE_SHADOW_STRENGTH, 0.0, 1.0);

    // sombra oscura y fría (azulada)
    vec3 shadowCol = diffuse.rgb * vec3(0.55, 0.62, 0.85);
    diffuse.rgb = mix(diffuse.rgb, shadowCol, shadow);

    // luz de sol ligeramente cálida en zonas iluminadas
    diffuse.rgb *= mix(vec3_splat(1.0), vec3(1.06, 1.02, 0.94), lit);
  }
  // ===== FIN SOMBRAS FALSAS =====

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

  gl_FragColor = diffuse;
}
