// Run inside Node before Pi can read the transcript or discover project extensions.
import { acquireWindowsLease } from "./windows-pi.ts";
const path=process.env.KOMPI_PI_RESUME_FILE;
if (!path) throw new Error("Missing resume transcript");
const cwd=acquireWindowsLease(path,"resume",process.env.KOMPI_PI_RESUME_ID);
if(typeof cwd!=="string") throw new Error("Invalid resume directory");
process.chdir(cwd);
