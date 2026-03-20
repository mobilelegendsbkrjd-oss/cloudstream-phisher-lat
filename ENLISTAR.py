import os

# 📂 Obtener el directorio donde está el script
ruta_base = os.path.dirname(os.path.abspath(__file__))

# 📄 Archivo de salida (se guardará en el mismo directorio)
archivo_salida = os.path.join(ruta_base, "listado_carpetas.txt")

with open(archivo_salida, "w", encoding="utf-8") as f:
    for raiz, carpetas, archivos in os.walk(ruta_base):
        nivel = raiz.replace(ruta_base, "").count(os.sep)
        indentacion = " " * 4 * nivel
        f.write(f"{indentacion}📁 {os.path.basename(raiz)}\n")

        sub_indentacion = " " * 4 * (nivel + 1)
        for archivo in archivos:
            f.write(f"{sub_indentacion}📄 {archivo}\n")

print("✅ Listado generado en:", archivo_salida)
