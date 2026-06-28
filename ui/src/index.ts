import { definePlugin } from "@halo-dev/ui-shared";
import { markRaw } from "vue";
import AiAssistantTab from "./views/AiAssistantTab.vue";

export default definePlugin({
  extensionPoints: {
    "plugin:self:tabs:create": () => {
      return [
        {
          id: "ai-assistant-chat",
          label: "老巫师 AI 助手",
          component: markRaw(AiAssistantTab),
          permissions: [],
        },
      ];
    },
  },
});
