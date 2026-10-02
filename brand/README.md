# Trantor brand kit

The logo of Trantor in every variant, the favicons and the colors. Use the SVGs where you can; the PNGs are for
tools that do not take SVG.

**light** means "to place on light backgrounds"; **dark**, "to place on dark backgrounds".

## Files

| Variant | SVG | PNG |
|---|---|---|
| Horizontal logo, with the name | `svg/trantor-logo-horizontal-{light,dark}.svg` | 512, 1024 and 2048 px wide |
| Stacked logo, with the name | `svg/trantor-logo-stacked-{light,dark}.svg` | 512 and 1024 px wide |
| Symbol alone, square canvas | `svg/trantor-symbol-{light,dark}.svg` | 32, 64, 128, 256, 512 and 1024 px |
| Name alone | `svg/trantor-wordmark-{light,dark}.svg` | 1024 px wide |
| One-color symbol | `svg/trantor-symbol-mono-{black,white}.svg` | — |

Every SVG and PNG has a transparent background. The T and the gaps of the ring are transparent too: they take the
color of whatever is behind them. Do not stretch the logo; keep its aspect ratio.

The light and dark variants share exactly the same outlines. The letters are real paths: no font file is needed or
included.

## Colors

| Use | Color |
|---|---|
| Violet of the ring and the crescent | `#A47AE4` |
| Planet and text, light variant | `#34283F` |
| Planet and text, dark variant | `#E8DFF3` |
| Light background of the preview | `#FAFAF8` |
| Dark background of the preview | `#16191F` |

The backgrounds are not part of the logos. The same values are in `colors.json`.

## On a web page

To follow the theme of the system:

```html
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="/brand/svg/trantor-logo-horizontal-dark.svg">
  <img src="/brand/svg/trantor-logo-horizontal-light.svg" alt="Trantor" width="240" height="70"
       style="display:block;max-width:100%;height:auto">
</picture>
```

When the site has its own theme switch, change the `src` with the site's theme instead of relying on
`prefers-color-scheme` alone. For the symbol alone, use `trantor-symbol-light.svg` or `trantor-symbol-dark.svg`
with equal width and height; when a visible "Trantor" sits next to it, give it `alt=""` so the name is not read
twice.

As a starting point, use the horizontal logo from 180 px wide and the symbol from 24 px. Below that, use the
favicons. The 32 px PNG is a faithful export, not a redrawing for pixels.

## Favicons

`favicon/` holds:

- `favicon.svg`: the transparent symbol, which follows the theme of the system.
- `favicon.ico`: 16, 32 and 48 px, on a fixed dark background so it is always visible.
- `icon-16.png`, `icon-32.png`, `icon-48.png`: raster versions on a dark background.
- `apple-touch-icon.png`: 180 × 180, on an opaque dark background.
- `icon-192.png` and `icon-512.png`: icons for the manifest, on an opaque dark background.
- `site.webmanifest`: the icons' configuration; on its own it does not make the site a PWA.

Copy the whole of `favicon/` to the public root of the site and add:

```html
<link rel="icon" href="/favicon.ico" sizes="any">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="/apple-touch-icon.png" sizes="180x180">
<link rel="manifest" href="/site.webmanifest">
```

The adaptive favicon follows the theme of the browser or the system, not a site's own theme switch.

## Preview

`preview.html` shows the final files on both backgrounds. `preview.png` is a reference sheet, not a logo to embed.
