#!/usr/bin/env node
// 移植后预检：拦住那些"编译通过、自测通过、装上去起不来"的问题。
//
// 三个**确定性**检查（不会误报）：
//   1. Fabric entrypoint 用 private 构造器 -> IllegalAccessException，模组不加载
//   2. mods.toml 里硬编码的 loaderVersion 与该 MC 版本的 loader 主版本不符
//   3. jar 里残留 META-INF/neoforge.mods.toml
//
// 另有一项**信息性**输出（不做判定）：每个 @EventBusSubscriber 类的总线标注与
// 订阅方法参数类型。Forge 1.20.1 及更早不允许一个类跨总线，但"哪个事件属于
// 哪条总线"在没有映射表的情况下容易判错，所以这里只列出来供人核对——
// 误报的检查器比没有检查器更糟。
//
// 用法：node preflight.mjs <项目根目录>
//   node preflight.mjs D:/tools/gt_src
//   node preflight.mjs "F:/vibe coding工程/sfx/worldreplay"

import fs from 'node:fs';
import path from 'node:path';

const root = process.argv[2];
if (!root || !fs.existsSync(root)) {
  console.error('用法: node preflight.mjs <项目根目录>');
  process.exit(2);
}

const modDirs = fs.readdirSync(root, { withFileTypes: true })
  .filter(e => e.isDirectory() && /^(fabric|forge|nf)-/.test(e.name))
  .map(e => e.name)
  .sort();

// MC 版本 -> Forge / NeoForge 的 loader 主版本（javafml 版本号）
const FORGE_MAJOR = { '1.18.2': 40, '1.19.2': 43, '1.19.4': 45, '1.20.1': 47, '1.21.1': 51 };
const NEOFORGE_MAJOR = { '1.21.1': 21 };

const problems = [];
const busReport = [];

function walk(d, out = []) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.java')) out.push(p);
  }
  return out;
}

for (const mod of modDirs) {
  const src = path.join(root, mod, 'src/main/java');
  const res = path.join(root, mod, 'src/main/resources');
  if (!fs.existsSync(src)) continue;

  const mcVer = (mod.match(/(\d+\.\d+(?:\.\d+)?)/) || [])[1] || '?';
  const isFabric = mod.startsWith('fabric');
  const isNeoForge = mod.startsWith('nf-');

  // ---------- 1. Fabric entrypoint 构造器 ----------
  if (isFabric) {
    for (const f of walk(src)) {
      const t = fs.readFileSync(f, 'utf8');
      if (!/implements\s+(ModInitializer|ClientModInitializer|DedicatedServerModInitializer)/.test(t)) continue;
      const base = path.basename(f, '.java');
      if (new RegExp(`private\\s+${base}\\s*\\(`).test(t)) {
        problems.push({
          mod, what: `Fabric entrypoint ${base} 用 private 构造器`,
          why: 'Loader 反射实例化入口类 → IllegalAccessException，整个模组不加载',
          fix: `改成 public ${base}()`,
        });
      }
    }
  }

  // ---------- 2. mods.toml 硬编码 loaderVersion ----------
  const toml = path.join(res, 'META-INF/mods.toml');
  if (fs.existsSync(toml)) {
    const t = fs.readFileSync(toml, 'utf8');
    const lv = t.match(/loaderVersion\s*=\s*"([^"]+)"/);
    if (lv && !lv[1].includes('${')) {
      const num = parseInt((lv[1].match(/(\d+)/) || [])[1] || '0', 10);
      const expect = isNeoForge ? NEOFORGE_MAJOR[mcVer] : FORGE_MAJOR[mcVer];
      if (expect && num !== expect) {
        problems.push({
          mod, what: `mods.toml loaderVersion="${lv[1]}"，但 MC ${mcVer} 的 loader 是 ${expect}`,
          why: `启动报 needs language provider javafml:${num} or above / found ${expect}`,
          fix: '改成 ${loader_version_range} 占位符，并在 build.gradle 的 replaceProperties 给出正确范围',
        });
      }
    }
  }

  // ---------- 3. neoforge.mods.toml 残留 ----------
  const stale = path.join(res, 'META-INF/neoforge.mods.toml');
  if (fs.existsSync(stale) && !isNeoForge) {
    problems.push({
      mod, what: 'resources 里有 META-INF/neoforge.mods.toml（NeoForge 遗留）',
      why: '会被打进 jar，占位符通常未展开，对 Forge 无用',
      fix: '删掉该文件',
    });
  }

  // ---------- 信息性：总线标注与订阅类型 ----------
  if (!isFabric) {
    for (const f of walk(src)) {
      const t = fs.readFileSync(f, 'utf8');
      const anns = [...t.matchAll(/@(?:Mod\.)?EventBusSubscriber\(([^)]*)\)/g)];
      if (anns.length === 0) continue;
      const base = path.basename(f, '.java');
      const buses = anns.map(a => {
        const m = a[1].match(/bus\s*=\s*[\w.]*?(\w+)\s*(?:,|$)/);
        return m ? m[1] : 'FORGE';
      });
      const subs = [...t.matchAll(/@SubscribeEvent\s+(?:public|private|protected)?\s*static\s+void\s+(\w+)\s*\(\s*([\w.]+)/g)]
        .map(m => `${m[1]}(${m[2]})`);
      busReport.push({ mod, cls: base, buses: [...new Set(buses)], subs });
    }
  }
  // ---------- 4. mixin 注入点写法 ----------
  // 两种真实事故：
  //   a) remap=false（目标第三方 mod）+ 描述符里写编译期的 Minecraft 类名
  //      -> 描述符不重映射，Fabric 运行时是 intermediary 名，永远匹配不上
  //   b) 描述符用点号（net.minecraft.client.gui.GuiGraphics）而不是 JVM 内部名
  //      -> Mixin 解析不出方法签名，注入失败
  // 两种都在 defaultRequire: 0 下静默回滚。最稳的写法：只写方法名。
  for (const f of walk(src)) {
    const t = fs.readFileSync(f, 'utf8');
    const mixin = t.match(/@Mixin\(([^)]*)\)/);
    if (!mixin) continue;
    const remapFalse = /remap\s*=\s*false/.test(mixin[1]);
    const base = path.basename(f, '.java');

    for (const m of t.matchAll(/@Inject\(\s*method\s*=\s*"([^"]+)"/g)) {
      const method = m[1];
      if (!method.includes('(')) continue;   // 只写方法名 = 安全写法

      // b) 点号描述符
      if (/\b[a-z]+(\.[a-z0-9]+)+\.[A-Z]\w*;/.test(method)) {
        problems.push({
          mod, what: `${base} 的注入描述符用了点号（${method.slice(0, 60)}…）`,
          why: 'JVM 方法描述符要求内部名（斜杠分隔），点号形式 Mixin 解析不出方法，注入静默失败',
          fix: '改成 @Inject(method = "方法名", ...)；目标有重载时才需要描述符，且必须用斜杠内部名',
        });
        continue;
      }

      // a) remap=false + 描述符里含 Minecraft 类名
      if (remapFalse && /L(net\/minecraft|com\/mojang)\//.test(method)) {
        if (isFabric) {
          problems.push({
            mod, what: `${base} 是 remap=false 却在描述符里写了编译期的 Minecraft 类名`,
            why: 'remap=false 时描述符不重映射；Fabric 运行时是 intermediary 名，注入永远匹配不上',
            fix: '改成只写方法名（目标无重载时），由 knot 统一映射参数类型',
          });
        }
        // Forge 运行时类名就是 mojmap，理论上能匹配，不报错
      }
    }
  }

}
console.log(`预检: ${root}`);
console.log(`模块: ${modDirs.join(', ')}`);
console.log('');

if (problems.length === 0) {
  console.log('✓ 确定性问题：无');
} else {
  console.log(`✗ 发现 ${problems.length} 个问题：`);
  for (const p of problems) {
    console.log('');
    console.log(`  [${p.mod}] ${p.what}`);
    console.log(`    原因: ${p.why}`);
    console.log(`    修法: ${p.fix}`);
  }
}

if (busReport.length) {
  console.log('');
  console.log('--- 信息：@EventBusSubscriber 的总线标注（需人工核对，未判定）---');
  console.log('    Forge 1.20.1 及更早：一个类只能属于一条总线；NeoForge 21.x 允许混用。');
  for (const r of busReport) {
    console.log(`  ${r.mod.padEnd(14)} ${r.cls.padEnd(22)} bus=${r.buses.join('/')}`);
    for (const s of r.subs) console.log(`      ${s}`);
  }
}

process.exit(problems.length > 0 ? 1 : 0);
