import { describe, expect, it } from "vitest";
import { InvalidInputError } from "../errors.js";
import {
  ALLOWED_UPLOAD_MIME_TYPES,
  isAllowedUploadType,
  MAX_UPLOAD_BYTES,
  UPLOAD_FILE_FIELD,
  uploadLimitRejection,
  uploadTypeRejection
} from "./upload-policy.js";

describe("documents/upload-policy", () => {
  it("sets the limit at 25MB", () => {
    expect(MAX_UPLOAD_BYTES).toBe(25 * 1024 * 1024);
  });

  it("accepts PDF, the common scan formats and Word", () => {
    for (const type of ["application/pdf", "image/jpeg", "image/png", "image/tiff", "application/msword"]) {
      expect(isAllowedUploadType(type)).toBe(true);
    }
    expect(isAllowedUploadType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")).toBe(true);
  });

  it("rejects an executable and anything else not on the list", () => {
    expect(isAllowedUploadType("application/x-msdownload")).toBe(false);
    expect(isAllowedUploadType("application/zip")).toBe(false);
    expect(isAllowedUploadType("")).toBe(false);
  });

  it("does not accept a type by prefix, which would let application/* through", () => {
    expect(isAllowedUploadType("application/pdf; charset=binary")).toBe(false);
  });

  describe("uploadTypeRejection", () => {
    it("is an InvalidInputError, so the apps map it to 400 rather than 500", () => {
      expect(uploadTypeRejection("application/zip")).toBeInstanceOf(InvalidInputError);
    });

    it("names the offending type in the message and attributes the error to the file input", () => {
      const rejection = uploadTypeRejection("application/zip");

      expect(rejection.message).toContain("application/zip");
      expect(rejection.errors).toEqual([{ field: UPLOAD_FILE_FIELD, message: "Upload a PDF, image or Word document" }]);
    });
  });

  describe("uploadLimitRejection", () => {
    it("states the limit in megabytes for an over-size file", () => {
      const rejection = uploadLimitRejection("LIMIT_FILE_SIZE");

      expect(rejection.message).toBe("The file must be smaller than 25MB");
      expect(rejection.errors[0].field).toBe(UPLOAD_FILE_FIELD);
    });

    it("derives the megabytes from the limit rather than restating it", () => {
      // Guards the pairing: changing MAX_UPLOAD_BYTES must change the message, or the form would
      // reject at one size while telling the caseworker another.
      expect(uploadLimitRejection("LIMIT_FILE_SIZE").message).toContain(String(Math.round(MAX_UPLOAD_BYTES / (1024 * 1024))));
    });

    it("asks for one file at a time when too many or an unexpected field arrive", () => {
      expect(uploadLimitRejection("LIMIT_FILE_COUNT").message).toBe("Upload one file at a time");
      expect(uploadLimitRejection("LIMIT_UNEXPECTED_FILE").message).toBe("Upload one file at a time");
    });

    it("falls back to a generic message for a code it does not know", () => {
      // Multer has codes this does not enumerate. A vague message beats an unmapped error becoming a
      // 500, which is the whole reason this function exists.
      expect(uploadLimitRejection("LIMIT_FIELD_KEY").message).toBe("The file could not be read");
      expect(uploadLimitRejection("")).toBeInstanceOf(InvalidInputError);
    });

    it("always attributes the failure to a field, so a form can render it in place", () => {
      for (const code of ["LIMIT_FILE_SIZE", "LIMIT_FILE_COUNT", "LIMIT_UNEXPECTED_FILE", "SOMETHING_ELSE"]) {
        expect(uploadLimitRejection(code).errors).toHaveLength(1);
      }
    });
  });

  it("uses the same field name both apps post the file under", () => {
    expect(UPLOAD_FILE_FIELD).toBe("file");
  });

  it("exposes the accepted set for anything that needs to list it", () => {
    expect(ALLOWED_UPLOAD_MIME_TYPES.size).toBe(6);
  });
});
