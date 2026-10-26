// Стиль офлайн-карты vabuGEOde: тёмная тема Protomaps, перекрашенная под «лёд»
const b = require('@protomaps/basemaps');
const ice = JSON.parse(JSON.stringify(b.DARK));
Object.assign(ice, {
  background: '#060A12', earth: '#0A101A', water: '#173D63',
  park_a: '#0C1A18', park_b: '#0C1A18', wood_a: '#0B1816', wood_b: '#0B1816',
  scrub_a: '#0D1617', scrub_b: '#0D1617', buildings: '#141E2C',
  other: '#7C93AE', minor_service: '#4A6182', minor_a: '#4A6182', minor_b: '#4A6182',
  link: '#5D7898', major: '#5D7898', highway: '#7A97BA',
  boundaries: '#4A5E7C', railway: '#1E2D44',
  city_label: '#B9D6EE', city_label_halo: '#060A12', subplace_label: '#7F98B4', subplace_label_halo: '#060A12',
  roads_label_minor: '#6F87A4', roads_label_minor_halo: '#060A12', roads_label_major: '#8CA3BD', roads_label_major_halo: '#060A12',
  ocean_label: '#5F86AE', state_label: '#4A5E7C', country_label: '#6A819E',
});
ice.landcover = { ...ice.landcover, forest: 'rgba(11, 26, 24, 1)', grassland: 'rgba(13, 24, 22, 1)', scrub: 'rgba(13, 22, 23, 1)', farmland: 'rgba(14, 22, 26, 1)' };
let layers = b.layers('protomaps', ice, { lang: 'ru' });
// Иконок POI нет (спрайт не везём) — убираем слой точек интереса целиком
layers = layers.filter(l => !['pois', 'roads_oneway', 'roads_shields'].includes(l.id));
for (const l of layers) for (const k of Object.keys(l.layout || {})) if (k.startsWith('icon-')) delete l.layout[k];
const style = {
  version: 8,
  name: 'vabuGEOde ice',
  glyphs: 'asset://fonts/{fontstack}/{range}.pbf',
  sources: { protomaps: { type: 'vector', url: '__PMTILES__', attribution: '© OpenStreetMap' } },
  layers,
};
const fonts = new Set();
for (const l of layers) { const f = l.layout && l.layout['text-font']; if (f) f.forEach(x => fonts.add(x)); }
require('fs').writeFileSync(process.argv[2] || 'style.json', JSON.stringify(style));
console.log(layers.length, 'layers; fonts:', [...fonts].join(', '));
console.log(layers.map(l=>l.id).join(' '));
