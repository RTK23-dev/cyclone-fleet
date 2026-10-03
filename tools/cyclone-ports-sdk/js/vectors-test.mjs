import fs from "node:fs";
import { verifyCyclone } from "./verify.mjs";
const v = JSON.parse(fs.readFileSync(process.argv[2])).vectors;
for (const c of v) {
  const [kid, ...rest] = c.secret.split(".");
  const keys = /^k\d+$/.test(kid) && rest.length ? { [kid]: rest.join(".") } : { k1: c.secret };
  const ok = verifyCyclone(keys, c.header, c.method, c.path, Buffer.from(c.body), new Map(), c.t);
  const replay = new Map(); verifyCyclone(keys, c.header, c.method, c.path, Buffer.from(c.body), replay, c.t);
  const second = verifyCyclone(keys, c.header, c.method, c.path, Buffer.from(c.body), replay, c.t);
  const tampered = verifyCyclone(keys, c.header, c.method, c.path + "x", Buffer.from(c.body), new Map(), c.t);
  console.log(c.name, ok, "replay:", second, "tampered:", tampered);
}
