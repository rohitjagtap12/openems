import { Environment } from "./environment.type";

export const environment: Environment = {
  production: false,
  backend: "openems",
  url: "ws://" + location.hostname + ":8085",
};
