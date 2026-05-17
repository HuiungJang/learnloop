import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import path from "node:path";
import { EMPTY_DB } from "./config.js";

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

export class JsonStore {
  constructor(dataDir) {
    this.dataDir = dataDir;
    this.dbPath = path.join(dataDir, "db.json");
    this.objectsDir = path.join(dataDir, "objects");
    this.db = clone(EMPTY_DB);
  }

  async init() {
    await mkdir(this.objectsDir, { recursive: true });
    try {
      const raw = await readFile(this.dbPath, "utf8");
      this.db = { ...clone(EMPTY_DB), ...JSON.parse(raw) };
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
      await this.save();
    }
  }

  async save() {
    await mkdir(this.dataDir, { recursive: true });
    const tmp = `${this.dbPath}.tmp`;
    await writeFile(tmp, `${JSON.stringify(this.db, null, 2)}\n`, "utf8");
    await rename(tmp, this.dbPath);
  }

  collection(name) {
    if (!Array.isArray(this.db[name])) {
      throw new Error(`Unknown collection: ${name}`);
    }
    return this.db[name];
  }

  async insert(name, row) {
    this.collection(name).push(row);
    await this.save();
    return row;
  }

  async update(name, id, patch) {
    const rows = this.collection(name);
    const index = rows.findIndex((row) => row.id === id);
    if (index === -1) return null;
    rows[index] = { ...rows[index], ...patch, updatedAt: new Date().toISOString() };
    await this.save();
    return rows[index];
  }

  async putObject(storageRef, content) {
    const filePath = this.objectPath(storageRef);
    await mkdir(path.dirname(filePath), { recursive: true });
    await writeFile(filePath, content, "utf8");
  }

  async getObject(storageRef) {
    return readFile(this.objectPath(storageRef), "utf8");
  }

  objectPath(storageRef) {
    const normalized = storageRef.replace(/^object:\/\//, "");
    return path.join(this.objectsDir, normalized);
  }
}
