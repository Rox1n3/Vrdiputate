/**
 * Утилита для фильтрации заявок по кварталу депутата.
 *
 * districts — массив { street, houses }, где:
 *   street — название улицы в нижнем регистре (без «ул.», «пр.» и т.п.)
 *   houses — список номеров домов (строки, точно как в документе: "93", "93/2", "10А", …)
 *
 * Алгоритм:
 *  1. Нормализуем адрес: нижний регистр, убираем «ул.», «улица», «пр.», «проспект»,
 *     «д.», «дом», знаки препинания → заменяем на пробел.
 *  2. Ищем название улицы в нормализованном адресе.
 *  3. Берём первый токен ПОСЛЕ найденного названия улицы — это должен быть номер дома.
 *  4. Проверяем, есть ли этот токен в списке houses.
 */

export interface DeputyDistrict {
  street: string;   // нижний регистр, без приставок (ул./пр./…)
  houses: string[]; // точные номера домов в нижнем регистре
}

export function addressMatchesDistricts(
  address: string,
  districts: DeputyDistrict[]
): boolean {
  const normalized = address
    .toLowerCase()
    // убираем стандартные приставки
    .replace(/\bул\.?\s*/g, ' ')
    .replace(/\bулица\s*/g, ' ')
    .replace(/\bпр\.?\s*/g, ' ')
    .replace(/\bпроспект\s*/g, ' ')
    .replace(/\bд\.?\s*/g, ' ')
    .replace(/\bдом\s*/g, ' ')
    .replace(/\bкв\.?\s*/g, ' квартира ')   // чтоб «кв.5» не слиплось с домом
    .replace(/[,;.]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();

  return districts.some(({ street, houses }) => {
    const streetLower = street.toLowerCase();
    const idx = normalized.indexOf(streetLower);
    if (idx === -1) return false;

    // текст, начиная сразу после названия улицы
    const afterStreet = normalized.slice(idx + streetLower.length).trim();
    // первый токен = номер дома (остальное — квартира, корпус и т.п.)
    const houseToken = afterStreet.split(/\s+/)[0] ?? '';

    return houses.some(h => h.toLowerCase() === houseToken);
  });
}
