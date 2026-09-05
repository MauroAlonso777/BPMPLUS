# Definiciones de proceso BPMN

Acá van los diagramas de proceso de la aplicación, un archivo por proceso, con extensión
**`.bpmn20.xml`** (Flowable solo trata como BPMN los recursos que terminan en `.bpmn20.xml` o
`.bpmn`).

`ProcessDefinitionDeployer` los despliega en la base de **cada tenant** al darlo de alta y en
cada arranque, con `enableDuplicateFiltering()`: si el archivo no cambió, no se crea una versión
nueva.

## Herramienta de modelado

**Camunda Modeler** (escritorio, Apache-2.0). Flowable 7 OSS eliminó su propio modeler, así que
el diagrama se edita fuera y se commitea acá como cualquier otro fuente.

### Al guardar, revisar dos cosas

1. **El namespace de las extensiones.** Camunda Modeler escribe sus propiedades como
   `camunda:...`. Flowable **las ignora en silencio**: el proceso se despliega, pero el
   `assignee`, la `expression` de una tarea de servicio o el `formKey` no hacen nada. Las
   extensiones tienen que ir con el prefijo `flowable:`, declarando en `<definitions>`:

   ```xml
   xmlns:flowable="http://flowable.org/bpmn"
   ```

   Para lo que Camunda Modeler no deja escribir en ese namespace, se edita el XML a mano.

2. **Que el archivo pase el spec.** `ProcessDefinitionValidationSpec` parsea y valida con
   Flowable todos los `.bpmn20.xml` de este directorio y falla si alguno no es desplegable o si
   quedaron atributos `camunda:` que Flowable descartaría. Corre con `./gradlew test`, así que
   un diagrama mal guardado no llega a producción.

## Convención de nombres

- Archivo: el `id` del proceso, en `lowerCamelCase`, más `.bpmn20.xml` — por ejemplo
  `altaExpediente.bpmn20.xml` para `<process id="altaExpediente">`.
- El `id` del proceso es su clave estable: se usa en `startProcessInstanceByKey` y no debería
  cambiar una vez que hay instancias en producción.
