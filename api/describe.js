module.exports = async function handler(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Only POST is allowed." });
  }

  const expectedToken = process.env.DIDYAR_APP_TOKEN;
  if (!expectedToken) {
    return res.status(500).json({ error: "DIDYAR_APP_TOKEN is not configured." });
  }

  const auth = req.headers.authorization || "";
  if (auth !== `Bearer ${expectedToken}`) {
    return res.status(401).json({ error: "مجوز سرویس دیدیار معتبر نیست." });
  }

  const apiKey = process.env.OPENAI_API_KEY;
  if (!apiKey) {
    return res.status(500).json({ error: "OPENAI_API_KEY is not configured." });
  }

  try {
    const body = typeof req.body === "string" ? JSON.parse(req.body) : (req.body || {});
    const frames = Array.isArray(body.frames) ? body.frames : [];

    if (frames.length < 1 || frames.length > 3) {
      return res.status(400).json({ error: "بین یک تا سه فریم لازم است." });
    }

    const tooLarge = frames.some(
      (frame) => typeof frame !== "string" || frame.length > 1_500_000
    );
    if (tooLarge) {
      return res.status(413).json({ error: "حجم یکی از تصاویر بیش از حد مجاز است." });
    }

    const sceneIndex = Number(body.scene_index || 1);
    const sceneTotal = Number(body.scene_total || 1);
    const timecode = String(body.timecode || "");

    const prompt = [
      "تو نویسندهٔ توضیح صوتی فارسی برای مخاطب نابینا هستی.",
      "تصاویر ورودی چند فریم متوالی از یک صحنهٔ فیلم هستند.",
      "در یک یا دو جملهٔ کوتاه و روان، فقط اطلاعات دیداری مهمی را بگو که احتمالاً از صدای فیلم فهمیده نمی‌شود.",
      "روی حرکت، ورود و خروج افراد، حالت کلی بدن، مکان، اشیای مهم، نوشتهٔ مهم روی تصویر و تغییر بصری اصلی تمرکز کن.",
      "هویت یا نام افراد را حدس نزن، دربارهٔ نیت یا احساسات قطعی حدس نزن و چیزی را که در تصاویر روشن نیست نساز.",
      "دیالوگ‌ها و صداهای قابل شنیدن را بازگو نکن.",
      "از عبارت‌هایی مثل «در تصویر می‌بینیم» استفاده نکن؛ مستقیم و طبیعی توصیف کن.",
      "خروجی فقط خود توضیح فارسی باشد، بدون عنوان، شماره‌گذاری یا توضیح اضافه.",
      `این صحنه شماره ${sceneIndex} از ${sceneTotal} و حوالی زمان ${timecode} است.`
    ].join("\n");

    const content = [
      { type: "input_text", text: prompt },
      ...frames.map((frame) => ({
        type: "input_image",
        image_url: `data:image/jpeg;base64,${frame}`,
        detail: "low"
      }))
    ];

    const openaiResponse = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${apiKey}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        model: process.env.OPENAI_MODEL || "gpt-5.6-luna",
        input: [{ role: "user", content }],
        max_output_tokens: 180
      })
    });

    const data = await openaiResponse.json();

    if (!openaiResponse.ok) {
      const detail = data?.error?.message || "OpenAI request failed.";
      return res.status(502).json({ error: detail });
    }

    let text = typeof data.output_text === "string" ? data.output_text : "";

    if (!text && Array.isArray(data.output)) {
      for (const item of data.output) {
        if (item?.type !== "message" || !Array.isArray(item.content)) continue;
        for (const part of item.content) {
          if (part?.type === "output_text" && typeof part.text === "string") {
            text += part.text;
          }
        }
      }
    }

    text = text.trim();
    if (!text) {
      return res.status(502).json({ error: "مدل توضیح متنی برنگرداند." });
    }

    return res.status(200).json({ description: text });
  } catch (error) {
    console.error(error);
    return res.status(500).json({
      error: error?.message || "خطای ناشناخته در سرویس دیدیار."
    });
  }
};
