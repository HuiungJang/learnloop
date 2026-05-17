import { DEFAULT_DATA_DIR, DEFAULT_PORT } from "./config.js";
import { createApp } from "./app.js";
import { JsonStore } from "./store.js";

const dataDir = process.env.APP_DATA_DIR || DEFAULT_DATA_DIR;
const port = Number(process.env.APP_PORT || DEFAULT_PORT);

const store = new JsonStore(dataDir);
await store.init();
const server = await createApp(store);

server.listen(port, () => {
  console.log(`AI Code Learning Platform listening on http://localhost:${port}`);
  console.log(`Data directory: ${dataDir}`);
});
