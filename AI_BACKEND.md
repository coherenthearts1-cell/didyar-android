# بک‌اند هوش مصنوعی دیدیار

این بک‌اند برای این ساخته شده که کلید OpenAI هرگز داخل APK قرار نگیرد.

## متغیرهای محیطی لازم در Vercel

- OPENAI_API_KEY: کلید API شخصی صاحب سرویس
- DIDYAR_APP_TOKEN: یک رمز طولانی و تصادفی برای دسترسی اپ به پروکسی
- OPENAI_MODEL: اختیاری. پیش‌فرض: gpt-5.6-luna

## Endpoint

پس از Deploy:
https://YOUR-PROJECT.vercel.app/api/describe

اپ اندروید فقط Endpoint و DIDYAR_APP_TOKEN را لازم دارد و OPENAI_API_KEY را دریافت نمی‌کند.

## ورودی

POST JSON:
- frames: یک تا سه تصویر JPEG به صورت Base64
- scene_index
- scene_total
- timecode

## خروجی

JSON با فیلد description.
