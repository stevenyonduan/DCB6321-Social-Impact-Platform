export const maxImageBytes = 5 * 1024 * 1024;
export const imageTypes = ['image/jpeg', 'image/png'];

export function isUploadImage(file: File) {
  return (
    imageTypes.includes(file.type) &&
    file.size > 0 &&
    file.size <= maxImageBytes
  );
}
